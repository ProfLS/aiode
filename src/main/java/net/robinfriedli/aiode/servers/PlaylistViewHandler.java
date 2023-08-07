package net.robinfriedli.aiode.servers;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.robinfriedli.aiode.Aiode;
import net.robinfriedli.aiode.discord.GuildManager;
import net.robinfriedli.aiode.entities.Playlist;
import net.robinfriedli.aiode.entities.PlaylistItem;
import net.robinfriedli.aiode.exceptions.InvalidRequestException;
import net.robinfriedli.aiode.util.SearchEngine;
import net.robinfriedli.aiode.util.Util;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

public class PlaylistViewHandler implements HttpHandler {

    private final ShardManager shardManager;
    private final SessionFactory sessionFactory;

    public PlaylistViewHandler(ShardManager shardManager, SessionFactory sessionFactory) {
        this.shardManager = shardManager;
        this.sessionFactory = sessionFactory;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Session session = null;
        try {
            String html = Files.readString(Path.of("html/playlist_view.html"));
            Map<String, String> parameterMap = ServerUtil.getParameters(exchange);
            String guildId = parameterMap.get("guildId");
            String name = parameterMap.get("name");

            boolean isPartitioned = Aiode.get().getGuildManager().getMode() == GuildManager.Mode.PARTITIONED;
            if (name != null && (guildId != null || !isPartitioned)) {
                session = sessionFactory.openSession();
                Playlist playlist = SearchEngine.searchLocalList(session, name, isPartitioned, guildId);
                if (playlist != null) {
                    String createdUserId = playlist.getCreatedUserId();
                    String createdUser;
                    if (createdUserId.equals("system")) {
                        createdUser = playlist.getCreatedUser();
                    } else {
                        User userById;
                        try {
                            userById = shardManager.retrieveUserById(createdUserId).complete();
                        } catch (ErrorResponseException e) {
                            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                                userById = null;
                            } else {
                                throw e;
                            }
                        }
                        createdUser = userById != null ? userById.getName() : playlist.getCreatedUser();
                    }
                    String htmlString = String.format(html,
                        playlist.getName(),
                        playlist.getName(),
                        createdUser, Util.normalizeMillis(playlist.getDuration()),
                        playlist.getSize(),
                        getList(playlist));

                    byte[] bytes = htmlString.getBytes();
                    exchange.sendResponseHeaders(200, bytes.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(bytes);
                    os.close();
                } else {
                    throw new InvalidRequestException("No playlist found");
                }
            } else {
                throw new InvalidRequestException("Insufficient request parameters");
            }
        } catch (InvalidRequestException e) {
            ServerUtil.handleError(exchange, e);
        } catch (Exception e) {
            ServerUtil.handleError(exchange, e);
            LoggerFactory.getLogger(getClass()).error("Error in HttpHandler", e);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private String getList(Playlist playlist) {
        StringBuilder listBuilder = new StringBuilder();
        List<PlaylistItem> playlistItems = playlist.getItemsSorted();
        for (int i = 0; i < playlistItems.size(); i++) {
            PlaylistItem item = playlistItems.get(i);
            listBuilder.append("<tr>").append(System.lineSeparator())
                .append("<td>").append(i + 1).append("</td>").append(System.lineSeparator())
                .append("<td>").append(item.display()).append("</td>").append(System.lineSeparator())
                .append("<td>").append(Util.normalizeMillis(item.getDuration())).append("</td>").append(System.lineSeparator())
                .append("</tr>").append(System.lineSeparator());
        }

        return listBuilder.toString();
    }

}
