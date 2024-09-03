package net.robinfriedli.aiode.command.commands.search;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import net.dv8tion.jda.api.EmbedBuilder;
import net.robinfriedli.aiode.Aiode;
import net.robinfriedli.aiode.audio.spotify.SpotifyTrack;
import net.robinfriedli.aiode.audio.youtube.YouTubePlaylist;
import net.robinfriedli.aiode.audio.youtube.YouTubeService;
import net.robinfriedli.aiode.audio.youtube.YouTubeVideo;
import net.robinfriedli.aiode.command.CommandContext;
import net.robinfriedli.aiode.command.CommandManager;
import net.robinfriedli.aiode.command.commands.AbstractSourceDecidingCommand;
import net.robinfriedli.aiode.command.widget.DynamicEmbedTablePaginationWidget;
import net.robinfriedli.aiode.command.widget.EmbedTablePaginationWidget;
import net.robinfriedli.aiode.command.widget.WidgetRegistry;
import net.robinfriedli.aiode.command.widget.widgets.PlaylistPaginationWidget;
import net.robinfriedli.aiode.entities.Playlist;
import net.robinfriedli.aiode.entities.xml.CommandContribution;
import net.robinfriedli.aiode.exceptions.InvalidCommandException;
import net.robinfriedli.aiode.exceptions.NoResultsFoundException;
import net.robinfriedli.aiode.exceptions.NoSpotifyResultsFoundException;
import net.robinfriedli.aiode.exceptions.UnavailableResourceException;
import net.robinfriedli.aiode.util.SearchEngine;
import net.robinfriedli.aiode.util.Util;
import net.robinfriedli.stringlist.StringList;
import org.hibernate.Session;
import se.michaelthelin.spotify.model_objects.specification.AlbumSimplified;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Episode;
import se.michaelthelin.spotify.model_objects.specification.PlaylistSimplified;
import se.michaelthelin.spotify.model_objects.specification.ShowSimplified;
import se.michaelthelin.spotify.model_objects.specification.Track;

public class SearchCommand extends AbstractSourceDecidingCommand {

    public SearchCommand(CommandContribution commandContribution, CommandContext commandContext, CommandManager commandManager, String commandString, boolean requiresInput, String identifier, String description, Category category) {
        super(commandContribution, commandContext, commandManager, commandString, requiresInput, identifier, description, category);
    }

    @Override
    public void doRun() throws Exception {
        Source source = getSource();
        if (argumentSet("list")) {
            if (source.isSpotify()) {
                listSpotifyList();
            } else if (source.isYouTube()) {
                listYouTubePlaylists();
            } else {
                listLocalList();
            }
        } else if (argumentSet("album")) {
            listSpotifyAlbum();
        } else if (argumentSet("episode")) {
            searchSpotifyEpisode();
        } else if (argumentSet("podcast")) {
            listSpotifyShow();
        } else {
            if (source.isYouTube()) {
                searchYouTubeVideo();
            } else {
                searchSpotifyTrack();
            }
        }
    }

    private void searchSpotifyTrack() throws Exception {
        if (getCommandInput().isBlank()) {
            throw new InvalidCommandException("No search term entered");
        }

        int limit = getArgumentValueWithTypeOrElse("select", Integer.class, 20);
        Callable<List<Track>> loadTrackCallable = () -> getSpotifyService().searchTrack(getCommandInput(), argumentSet("own"), limit);
        List<Track> found;
        if (argumentSet("own")) {
            found = runWithLogin(loadTrackCallable);
        } else {
            found = runWithCredentials(loadTrackCallable);
        }
        if (!found.isEmpty()) {
            EmbedBuilder embedBuilder = new EmbedBuilder();

            Util.appendEmbedList(
                embedBuilder,
                found,
                track -> track.getName() + " - " + track.getAlbum().getName() + " - " +
                    StringList.create(track.getArtists(), ArtistSimplified::getName).toSeparatedString(", "),
                "Track - Album - Artist"
            );

            sendMessage(embedBuilder);
        } else {
            throw new NoSpotifyResultsFoundException(String.format("No Spotify track found for '%s'", getCommandInput()));
        }
    }

    private void searchSpotifyEpisode() throws Exception {
        if (getCommandInput().isBlank()) {
            throw new InvalidCommandException("No search term entered");
        }

        int limit = getArgumentValueWithTypeOrElse("select", Integer.class, 20);
        Callable<List<Episode>> loadTrackCallable = () -> getSpotifyService().searchEpisode(getCommandInput(), argumentSet("own"), limit);
        List<Episode> found;
        if (argumentSet("own")) {
            found = runWithLogin(loadTrackCallable);
        } else {
            found = runWithCredentials(loadTrackCallable);
        }
        if (!found.isEmpty()) {
            EmbedBuilder embedBuilder = new EmbedBuilder();

            Util.appendEmbedList(
                embedBuilder,
                found,
                episode -> episode.getName() + " - " + episode.getShow().getName() + " - " + episode.getShow().getPublisher(),
                "Episode - Show - Publisher"
            );

            sendMessage(embedBuilder);
        } else {
            throw new NoSpotifyResultsFoundException(String.format("No podcast episode found for '%s'", getCommandInput()));
        }
    }

    private void searchYouTubeVideo() throws UnavailableResourceException, IOException {
        YouTubeService youTubeService = Aiode.get().getAudioManager().getYouTubeService();
        if (argumentSet("select")) {
            int limit = getArgumentValueWithTypeOrElse("select", Integer.class, 10);

            List<YouTubeVideo> youTubeVideos = youTubeService.searchSeveralVideos(limit, getCommandInput());
            if (youTubeVideos.size() == 1) {
                listYouTubeVideo(youTubeVideos.get(0));
            } else if (youTubeVideos.isEmpty()) {
                throw new NoResultsFoundException(String.format("No YouTube videos found for '%s'", getCommandInput()));
            } else {
                askQuestion(youTubeVideos, youTubeVideo -> {
                    try {
                        return youTubeVideo.getDisplay();
                    } catch (UnavailableResourceException e) {
                        // Unreachable since only HollowYouTubeVideos might get cancelled
                        throw new RuntimeException(e);
                    }
                });
            }
        } else {
            listYouTubeVideo(youTubeService.searchVideo(getCommandInput()));
        }
    }

    private void listYouTubeVideo(YouTubeVideo youTubeVideo) throws UnavailableResourceException {
        StringBuilder responseBuilder = new StringBuilder();
        responseBuilder.append("Title: ").append(youTubeVideo.getDisplay()).append(System.lineSeparator());
        responseBuilder.append("Id: ").append(youTubeVideo.getVideoId()).append(System.lineSeparator());
        responseBuilder.append("Link: ").append("https://www.youtube.com/watch?v=").append(youTubeVideo.getVideoId()).append(System.lineSeparator());
        responseBuilder.append("Duration: ").append(Util.normalizeMillis(youTubeVideo.getDuration()));

        sendMessage(responseBuilder.toString());
    }

    private void listLocalList() {
        WidgetRegistry widgetRegistry = getContext().getGuildContext().getWidgetRegistry();
        if (getCommandInput().isBlank()) {
            Session session = getContext().getSession();
            List<Playlist> playlists = getQueryBuilderFactory()
                .find(Playlist.class)
                .orderBy((from, cb) -> cb.asc(from.get("name")))
                .build(session)
                .getResultList();

            if (playlists.isEmpty()) {
                EmbedBuilder embedBuilder = new EmbedBuilder();
                embedBuilder.setDescription("No playlists");
                sendMessage(embedBuilder);
            } else {
                DynamicEmbedTablePaginationWidget<Playlist> paginationWidget = new DynamicEmbedTablePaginationWidget<Playlist>(
                    widgetRegistry,
                    getContext().getGuild(),
                    getContext().getChannel(),
                    "Playlists",
                    null,
                    new EmbedTablePaginationWidget.Column[]{
                        new EmbedTablePaginationWidget.Column<>("Playlist", Playlist::getName),
                        new EmbedTablePaginationWidget.Column<Playlist>("Duration", playlist -> Util.normalizeMillis(playlist.getDuration())),
                        new EmbedTablePaginationWidget.Column<Playlist>("Items", playlist -> String.valueOf(playlist.getSize())),
                    },
                    playlists
                );
                paginationWidget.initialise();
            }
        } else {
            Playlist playlist = SearchEngine.searchLocalList(getContext().getSession(), getCommandInput());
            if (playlist == null) {
                throw new NoResultsFoundException(String.format("No local list found for '%s'", getCommandInput()));
            }

            PlaylistPaginationWidget playlistPaginationWidget = new PlaylistPaginationWidget(
                widgetRegistry,
                getContext().getGuild(),
                getContext().getChannel(),
                playlist
            );
            playlistPaginationWidget.initialise();
        }
    }

    private void listYouTubePlaylists() throws IOException {
        YouTubeService youTubeService = Aiode.get().getAudioManager().getYouTubeService();
        if (argumentSet("select")) {
            int limit = getArgumentValueWithTypeOrElse("select", Integer.class, 10);

            List<YouTubePlaylist> playlists = youTubeService.searchSeveralPlaylists(limit, getCommandInput());
            if (playlists.size() == 1) {
                listYouTubePlaylist(playlists.get(0));
            } else if (playlists.isEmpty()) {
                throw new NoResultsFoundException(String.format("No YouTube playlist found for '%s'", getCommandInput()));
            } else {
                askQuestion(playlists, YouTubePlaylist::getTitle, YouTubePlaylist::getChannelTitle);
            }
        } else {
            listYouTubePlaylist(youTubeService.searchPlaylist(getCommandInput()));
        }
    }

    private void listYouTubePlaylist(YouTubePlaylist youTubePlaylist) {
        if (getCommandInput().isBlank()) {
            throw new InvalidCommandException("Command body may not be empty when searching YouTube list");
        }

        StringBuilder responseBuilder = new StringBuilder();
        responseBuilder.append("Title: ").append(youTubePlaylist.getTitle()).append(System.lineSeparator());
        responseBuilder.append("Url: ").append(youTubePlaylist.getUrl()).append(System.lineSeparator());
        responseBuilder.append("Videos: ").append(youTubePlaylist.getVideos().size()).append(System.lineSeparator());
        responseBuilder.append("Owner: ").append(youTubePlaylist.getChannelTitle());

        sendMessage(responseBuilder.toString());
    }

    private void listSpotifyList() throws Exception {
        String commandBody = getCommandInput();

        if (commandBody.isBlank()) {
            throw new InvalidCommandException("Command may not be empty when searching spotify lists");
        }

        List<PlaylistSimplified> playlists;
        int limit = getArgumentValueWithTypeOrElse("select", Integer.class, 20);
        if (argumentSet("own")) {
            playlists = runWithLogin(() -> getSpotifyService().searchOwnPlaylist(getCommandInput(), limit));
        } else {
            playlists = runWithCredentials(() -> getSpotifyService().searchPlaylist(getCommandInput(), limit));
        }
        if (playlists.size() == 1) {
            PlaylistSimplified playlist = playlists.get(0);
            List<SpotifyTrack> tracks = runWithCredentials(() -> getSpotifyService().getPlaylistTracks(playlist));
            listTracks(tracks, playlist.getName(), playlist.getOwner().getDisplayName(), null, "playlist/" + playlist.getId());
        } else if (playlists.isEmpty()) {
            throw new NoSpotifyResultsFoundException(String.format("No Spotify playlist found for '%s'", getCommandInput()));
        } else {
            askQuestion(playlists, PlaylistSimplified::getName, p -> p.getOwner().getDisplayName());
        }
    }

    private void listSpotifyAlbum() throws Exception {
        Integer limit = getArgumentValueWithTypeOrElse("select", Integer.class, 20);
        Callable<List<AlbumSimplified>> loadAlbumsCallable = () -> getSpotifyService().searchAlbum(getCommandInput(), argumentSet("own"), limit);
        List<AlbumSimplified> albums;
        if (argumentSet("own")) {
            albums = runWithLogin(loadAlbumsCallable);
        } else {
            albums = runWithCredentials(loadAlbumsCallable);
        }

        if (albums.size() == 1) {
            AlbumSimplified album = albums.get(0);
            List<SpotifyTrack> tracks = runWithCredentials(() -> getSpotifyService().getAlbumTracks(album.getId()))
                .stream()
                .filter(Objects::nonNull)
                .map(SpotifyTrack::wrap)
                .collect(Collectors.toList());
            listTracks(
                tracks,
                album.getName(),
                null,
                StringList.create(album.getArtists(), ArtistSimplified::getName).toSeparatedString(", "),
                "album/" + album.getId()
            );
        } else if (albums.isEmpty()) {
            throw new NoSpotifyResultsFoundException(String.format("No album found for '%s'", getCommandInput()));
        } else {
            askQuestion(albums, AlbumSimplified::getName, album -> StringList.create(album.getArtists(), ArtistSimplified::getName).toSeparatedString(", "));
        }
    }

    private void listSpotifyShow() throws Exception {
        int limit = getArgumentValueWithTypeOrElse("select", Integer.class, 20);
        Callable<List<ShowSimplified>> loadShowsCallable = () -> getSpotifyService().searchShow(getCommandInput(), argumentSet("own"), limit);
        List<ShowSimplified> shows;
        if (argumentSet("own")) {
            shows = runWithLogin(loadShowsCallable);
        } else {
            shows = runWithCredentials(loadShowsCallable);
        }

        if (shows.size() == 1) {
            ShowSimplified show = shows.get(0);
            List<SpotifyTrack> tracks = runWithCredentials(() -> getSpotifyService().getShowEpisodes(show.getId()))
                .stream()
                .filter(Objects::nonNull)
                .map(SpotifyTrack::wrap)
                .collect(Collectors.toList());
            listTracks(tracks, show.getName(), show.getPublisher(), null, "show/" + show.getId());
        } else if (shows.isEmpty()) {
            throw new NoSpotifyResultsFoundException(String.format("No Spotify playlist found for '%s'", getCommandInput()));
        } else {
            askQuestion(shows, ShowSimplified::getName, ShowSimplified::getPublisher);
        }
    }

    private void listTracks(List<SpotifyTrack> spotifyTracks, String name, String owner, String artist, String path) {
        EmbedBuilder embedBuilder = new EmbedBuilder();
        long totalDuration = spotifyTracks.stream().mapToInt(track -> {
            Integer durationMs = track.getDurationMs();
            return Objects.requireNonNullElse(durationMs, 0);
        }).sum();

        embedBuilder.addField("Name", name, true);
        embedBuilder.addField("Song count", String.valueOf(spotifyTracks.size()), true);
        embedBuilder.addField("Duration", Util.normalizeMillis(totalDuration), true);
        if (owner != null) {
            embedBuilder.addField("Owner", owner, true);
        }
        if (artist != null) {
            embedBuilder.addField("Artist", artist, true);
        }

        if (!spotifyTracks.isEmpty()) {
            String url = "https://open.spotify.com/" + path;
            embedBuilder.addField("First tracks:", "[Full list](" + url + ")", false);

            Util.appendEmbedList(
                embedBuilder,
                spotifyTracks.size() > 5 ? spotifyTracks.subList(0, 5) : spotifyTracks,
                spotifyTrack -> spotifyTrack.exhaustiveMatch(
                    track -> String.format("%s - %s - %s",
                        track.getName(),
                        StringList.create(track.getArtists(), ArtistSimplified::getName).toSeparatedString(", "),
                        Util.normalizeMillis(track.getDurationMs() != null ? track.getDurationMs() : 0)
                    ),
                    episode -> String.format("%s - %s - %s",
                        episode.getName(),
                        episode.getShow() != null ? episode.getShow().getName() : "",
                        Util.normalizeMillis(episode.getDurationMs() != null ? episode.getDurationMs() : 0)
                    )
                ),
                "Track - Artist - Duration"
            );
        }

        sendMessage(embedBuilder);
    }

    @Override
    public void onSuccess() {
    }

    @Override
    public void withUserResponse(Object chosenOption) throws Exception {
        if (chosenOption instanceof Collection) {
            throw new InvalidCommandException("Cannot select more than one result");
        }

        if (chosenOption instanceof PlaylistSimplified) {
            PlaylistSimplified playlist = (PlaylistSimplified) chosenOption;
            List<SpotifyTrack> tracks = runWithCredentials(() -> getSpotifyService().getPlaylistTracks(playlist));
            listTracks(tracks, playlist.getName(), playlist.getOwner().getDisplayName(), null, "playlist/" + playlist.getId());
        } else if (chosenOption instanceof YouTubePlaylist) {
            listYouTubePlaylist((YouTubePlaylist) chosenOption);
        } else if (chosenOption instanceof YouTubeVideo) {
            listYouTubeVideo((YouTubeVideo) chosenOption);
        } else if (chosenOption instanceof AlbumSimplified) {
            AlbumSimplified album = (AlbumSimplified) chosenOption;
            List<SpotifyTrack> tracks = runWithCredentials(() -> getSpotifyService().getAlbumTracks(album.getId()))
                .stream()
                .filter(Objects::nonNull)
                .map(SpotifyTrack::wrap)
                .collect(Collectors.toList());
            listTracks(tracks,
                album.getName(),
                null,
                StringList.create(album.getArtists(), ArtistSimplified::getName).toSeparatedString(", "),
                "album/" + album.getId());
        } else if (chosenOption instanceof ShowSimplified) {
            ShowSimplified show = (ShowSimplified) chosenOption;
            List<SpotifyTrack> tracks = runWithCredentials(() -> getSpotifyService().getShowEpisodes(show.getId()))
                .stream()
                .filter(Objects::nonNull)
                .map(SpotifyTrack::wrap)
                .collect(Collectors.toList());
            listTracks(tracks, show.getName(), show.getPublisher(), null, "show/" + show.getId());
        }
    }

}
