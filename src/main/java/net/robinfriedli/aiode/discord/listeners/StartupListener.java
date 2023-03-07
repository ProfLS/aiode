package net.robinfriedli.aiode.discord.listeners;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.robinfriedli.aiode.Aiode;
import net.robinfriedli.aiode.concurrent.CommandExecutionQueueManager;
import net.robinfriedli.aiode.concurrent.LoggingThreadFactory;
import net.robinfriedli.aiode.discord.GuildManager;
import net.robinfriedli.aiode.entities.xml.StartupTaskContribution;
import net.robinfriedli.aiode.persist.StaticSessionProvider;
import net.robinfriedli.jxp.api.JxpBackend;
import net.robinfriedli.jxp.persist.Context;
import org.discordbots.api.client.DiscordBotListAPI;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class StartupListener extends ListenerAdapter {

    private static final ExecutorService STARTUP_TASK_EXECUTOR = new ThreadPoolExecutor(
        0, 1,
        60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(),
        new LoggingThreadFactory("startup-task")
    );

    private final Aiode aiode;
    @Nullable
    private final DiscordBotListAPI discordBotListAPI;
    private final List<StartupTaskContribution> startupTaskContributions;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private StartupListener(@Lazy Aiode aiode, @Nullable DiscordBotListAPI discordBotListAPI, JxpBackend jxpBackend) {
        this.aiode = aiode;
        this.discordBotListAPI = discordBotListAPI;
        InputStream startupTasksResource = getClass().getResourceAsStream("/xml-contributions/startupTasks.xml");
        Context startupTaskContext = jxpBackend.createContext(startupTasksResource);
        startupTaskContributions = startupTaskContext.getInstancesOf(StartupTaskContribution.class);
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        STARTUP_TASK_EXECUTOR.execute(() -> {
            JDA jda = event.getJDA();

            if (discordBotListAPI != null) {
                try {
                    JDA.ShardInfo shardInfo = jda.getShardInfo();
                    discordBotListAPI.setStats(shardInfo.getShardId(), shardInfo.getShardTotal(), (int) jda.getGuildCache().size());
                } catch (Exception e) {
                    logger.error("Exception setting discordBotListAPI stats", e);
                }
            }

            CommandExecutionQueueManager executionQueueManager = aiode.getExecutionQueueManager();
            GuildManager guildManager = aiode.getGuildManager();

            StaticSessionProvider.consumeSession(session -> {
                // setup current thread session and handle all guilds within one session instead of opening a new session for each
                for (Guild guild : jda.getGuilds()) {
                    executionQueueManager.addGuild(guild);
                    guildManager.addGuild(guild);
                }
            });

            for (StartupTaskContribution element : startupTaskContributions) {
                if (element.getAttribute("runForEachShard").getBool()) {
                    try {
                        element.instantiate().runTask(jda);
                    } catch (Exception e) {
                        String msg = String.format(
                            "Startup task %s has thrown an exception for shard %s",
                            element.getAttribute("implementation").getValue(),
                            jda
                        );
                        logger.error(msg, e);
                    }
                }
            }
        });
    }

}
