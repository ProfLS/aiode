package net.robinfriedli.aiode.command.commands.playback;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import net.robinfriedli.aiode.Aiode;
import net.robinfriedli.aiode.audio.AudioManager;
import net.robinfriedli.aiode.audio.playables.PlayableContainer;
import net.robinfriedli.aiode.audio.playables.PlayableContainerManager;
import net.robinfriedli.aiode.audio.playables.PlayableFactory;
import net.robinfriedli.aiode.audio.queue.AudioQueue;
import net.robinfriedli.aiode.audio.queue.QueueFragment;
import net.robinfriedli.aiode.command.CommandContext;
import net.robinfriedli.aiode.command.CommandManager;
import net.robinfriedli.aiode.command.commands.AbstractQueueLoadingCommand;
import net.robinfriedli.aiode.entities.xml.CommandContribution;
import net.robinfriedli.aiode.exceptions.NoResultsFoundException;
import net.robinfriedli.aiode.exceptions.UserException;

public class NextCommand extends AbstractQueueLoadingCommand {

    public NextCommand(CommandContribution commandContribution, CommandContext context, CommandManager commandManager, String commandString, boolean requiresInput, String identifier, String description, Category category) {
        super(
                commandContribution,
                context,
                commandManager,
                commandString,
                requiresInput,
                identifier,
                description,
                category,
                context.getGuildContext().getPooledTrackLoadingExecutor()
        );
    }

    @Override public void doRun() throws Exception {
        if (getCommandInput().isBlank()) {
            throw new UserException("This command requires an input title or link.");
        } else {
            super.doRun();
        }
    }

    @Override
    protected void handleResult(PlayableContainer<?> playableContainer, PlayableFactory playableFactory) {
        AudioQueue audioQueue = getContext().getGuildContext().getPlayback().getAudioQueue();
        QueueFragment queueFragment = playableContainer.createQueueFragment(playableFactory, audioQueue);
        if (queueFragment == null) {
            throw new NoResultsFoundException("Nothing was found! Error with playableFactory.");
        }

        audioQueue.insert(audioQueue.getPosition() + 1, queueFragment);
    }

    @Override
    public void onSuccess() {
        if (loadedTrack != null) {
            sendSuccess("Playing " + loadedTrack.display() + " next");
        }
        if (loadedLocalList != null) {
            sendSuccess(String.format("Playing playlist '%s' next", loadedLocalList.getName()));
        }
        if (loadedSpotifyPlaylist != null) {
            sendSuccess(String.format("Playing Spotify playlist '%s' next", loadedSpotifyPlaylist.getName()));
        }
        if (loadedYouTubePlaylist != null) {
            sendSuccess(String.format("Playing Youtube playlist '%s' next", loadedYouTubePlaylist.getTitle()));
        }
        if (loadedAlbum != null) {
            sendSuccess(String.format("Playing album '%s' next", loadedAlbum.getName()));
        }
        if (loadedAmount > 0) {
            sendSuccess(String.format("Playing %d item%s tracks then resuming queue", loadedAmount, loadedAmount == 1 ? "" : "s"));
        }
        if (loadedAudioTrack != null) {
            sendSuccess("Playing track " + loadedAudioTrack.getInfo().title + " next");
        }
        if (loadedAudioPlaylist != null) {
            String name = loadedAudioPlaylist.getName();
            if (!Strings.isNullOrEmpty(name)) {
                sendSuccess("Playing playlist " + name + " next");
            } else {
                int size = loadedAudioPlaylist.getTracks().size();
                sendSuccess(String.format("Playing %d item%s then resuming queue", size, size == 1 ? "" : "s"));
            }
        }
        if (loadedShow != null) {
            String name = loadedShow.getName();
            sendSuccess("Playing podcast " + name + " next");
        }
    }
    @Override
    public void withUserResponse(Object chosenOption) throws Exception {
        Aiode aiode = Aiode.get();
        AudioManager audioManager = aiode.getAudioManager();
        PlayableContainerManager playableContainerManager = aiode.getPlayableContainerManager();
        PlayableFactory playableFactory = audioManager.createPlayableFactory(getSpotifyService(), getTrackLoadingExecutor(), shouldRedirectSpotify());
        AudioQueue queue = audioManager.getQueue(getContext().getGuild());

        List<PlayableContainer<?>> playableContainers;
        if (chosenOption instanceof Collection collection) {
            playableContainers = Lists.newArrayList();
            for (Object o : collection) {
                playableContainers.add(playableContainerManager.requirePlayableContainer(o));
            }
        } else {
            playableContainers = Collections.singletonList(playableContainerManager.requirePlayableContainer(chosenOption));
        }

        int prevSize = queue.getSize();
        queue.addContainers(playableContainers, playableFactory, false);
        loadedAmount = queue.getSize() - prevSize;
    }
}
