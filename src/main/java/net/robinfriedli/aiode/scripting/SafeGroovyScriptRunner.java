package net.robinfriedli.aiode.scripting;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import groovy.lang.GroovyShell;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.robinfriedli.aiode.Aiode;
import net.robinfriedli.aiode.boot.ShutdownableExecutorService;
import net.robinfriedli.aiode.boot.configurations.GroovySandboxComponent;
import net.robinfriedli.aiode.command.SecurityManager;
import net.robinfriedli.aiode.concurrent.ExecutionContext;
import net.robinfriedli.aiode.concurrent.ForkTaskThreadPool;
import net.robinfriedli.aiode.concurrent.LoggingThreadFactory;
import net.robinfriedli.aiode.discord.MessageService;
import net.robinfriedli.aiode.discord.property.properties.ColorSchemeProperty;
import net.robinfriedli.aiode.entities.StoredScript;
import net.robinfriedli.aiode.exceptions.CommandFailure;
import net.robinfriedli.aiode.exceptions.ExceptionUtils;
import net.robinfriedli.threadpool.ThreadPool;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;

/**
 * Class that provides safe execution of untrusted groovy scripts by setting up a sandboxed {@link GroovyShell} with compilation
 * customizers that check method and property access, transforms method invocations for which an invocation limit is set
 * to check and increment the invocation counter, adds a total limit of loop iterations and method invocations and enables
 * running scripts with a time limit by injecting interrupt checks into the script. The security sandbox can be disabled
 * using the isPrivileged constructor parameter, reducing the number of applied compilation customizers and ignoring time
 * limits while still applying the ImportCustomizer and ThreadInterrupt customizer to enable optionally interrupting the
 * script manually using the abort command. Whitelisted methods and properties are configured in the groovyWhitelist.xml
 * file.
 */
public class SafeGroovyScriptRunner {

    private static final ForkTaskThreadPool GLOBAL_POOL = new ForkTaskThreadPool(
        ThreadPool.Builder.create()
            .setCoreSize(3)
            .setMaxSize(Integer.MAX_VALUE)
            .setKeepAlive(60L, TimeUnit.SECONDS)
            .setWorkQueue(new SynchronousQueue<>())
            .setThreadFactory(new LoggingThreadFactory("script-execution-pool"))
            .build()
    );

    static {
        Aiode.SHUTDOWNABLES.add(new ShutdownableExecutorService(GLOBAL_POOL));
    }

    private final ExecutionContext context;
    private final GroovySandboxComponent groovySandboxComponent;
    private final GroovyVariableManager groovyVariableManager;
    private final GroovyWhitelistManager groovyWhitelistManager;
    private final SecurityManager securityManager;
    private final boolean isPrivileged;

    public SafeGroovyScriptRunner(
        ExecutionContext context,
        GroovySandboxComponent groovySandboxComponent,
        GroovyVariableManager groovyVariableManager,
        SecurityManager securityManager,
        boolean isPrivileged
    ) {
        this.context = context;
        this.groovySandboxComponent = groovySandboxComponent;
        this.groovyWhitelistManager = groovySandboxComponent.getGroovyWhitelistManager();
        this.groovyVariableManager = groovyVariableManager;
        this.securityManager = securityManager;
        this.isPrivileged = isPrivileged;
    }

    public void runScripts(List<StoredScript> scripts, String usageId) {
        AtomicReference<StoredScript> currentScriptReference = new AtomicReference<>();
        try {
            doRunScripts(scripts, currentScriptReference, 5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable error = e.getCause() != null ? e.getCause() : e;
            MessageService messageService = Aiode.get().getMessageService();

            if (error instanceof CommandFailure) {
                messageService.sendError(
                    String.format("Executing command %1$ss failed due to an error in %1$s '%2$s'", usageId, currentScriptReference.get().getIdentifier()),
                    context.getChannel()
                );
            } else {
                EmbedBuilder embedBuilder = ExceptionUtils.buildErrorEmbed(error);
                StoredScript currentScript = currentScriptReference.get();
                embedBuilder.setTitle(String.format("Error occurred while executing custom command %s%s",
                    usageId,
                    currentScript.getIdentifier() != null ? ": " + currentScript.getIdentifier() : "")
                );
                messageService.sendTemporary(embedBuilder.build(), context.getChannel());
            }
        } catch (TimeoutException e) {
            StoredScript currentScript = currentScriptReference.get();
            MessageService messageService = Aiode.get().getMessageService();
            messageService.sendError(
                String.format("Execution of script command %ss stopped because script%s has run into a timeout",
                    usageId,
                    currentScript != null ? String.format(" '%s'", currentScript.getIdentifier()) : ""),
                context.getChannel()
            );
        }
    }

    /**
     * Run all provided scripts sequentially in the same thread. This counts as one single script execution, thus all scripts
     * share the same method invocation limits and run with one time limit. This method is mainly used by the ScriptCommandInterceptor
     * to run all interceptors or all finalizers. Just like {@link #evaluateScript(String, long, TimeUnit)} this applies
     * all security checks and expression transformation if isPrivileged is false.
     *
     * @param scripts       a list of {@link StoredScript} entities representing the scripts to run
     * @param currentScript a reference pointing to the script that is currently being executed, can be used to reference
     *                      the last executed script if execution fails or execution runs into a timeout
     * @param timeout       the time delta in which all provided scripts have to run, ignored if isPrivileged is true
     * @param timeUnit      the time unit of the timeout
     * @throws ExecutionException if a script fails due to an exception
     * @throws TimeoutException   if not all scripts finish within the given time limit
     */
    public void doRunScripts(List<StoredScript> scripts, AtomicReference<StoredScript> currentScript, long timeout, TimeUnit timeUnit) throws ExecutionException, TimeoutException {
        GroovyShell groovyShell = createShell();
        Future<Object> result = scriptExecution(() -> {
            for (StoredScript script : scripts) {
                currentScript.set(script);
                groovyShell.evaluate(script.getScript());
            }
            return null;
        });

        runScriptWithTimeout(result, timeout, timeUnit);
    }

    /**
     * Evaluate the script by running it using the groovy shell set up for this instance. If isPrivileged is false
     * static compilation, the type checking extensions to check whitelisted method invocations and property access and
     * other compilation customizers are applied and the script runs under a timeout.
     *
     * @param script   the groovy code to run
     * @param timeout  the timeout, ignored if isPrivileged is true
     * @param timeUnit the time unit for the timout parameter
     * @return the object returned by the script
     * @throws ExecutionException if script execution throws an exection
     * @throws TimeoutException   if the script runs into a timeout
     */
    public Object evaluateScript(String script, long timeout, TimeUnit timeUnit) throws ExecutionException, TimeoutException {
        GroovyShell groovyShell = createShell();
        Future<Object> result = scriptExecution(() -> groovyShell.evaluate(script));

        return runScriptWithTimeout(result, timeout, timeUnit);
    }

    /**
     * Run a groovy script and send its result, returned object or thrown exception, to the channel of the {@link ExecutionContext}
     * this instance was set up with. This method calls {@link #evaluateScript(String, long, TimeUnit)} and handles the
     * result by sending the string representation of the result or an error message should an error occur. If the script
     * returns null this method sends nothing.
     *
     * @param script   the groovy code to run
     * @param timeout  the timeout, ignored if isPrivileged is true
     * @param timeUnit the time unit for the timout parameter
     */
    public void runAndSendResult(String script, long timeout, TimeUnit timeUnit) {
        MessageService messageService = Aiode.get().getMessageService();
        MessageChannel channel = context.getChannel();
        try {
            Object result = evaluateScript(script, timeout, timeUnit);
            if (result != null) {
                String resultString = result.toString();
                if (resultString.length() < 1000) {
                    EmbedBuilder embedBuilder = new EmbedBuilder();
                    embedBuilder.setTitle("Output");
                    embedBuilder.setDescription("```" + resultString + "```");
                    messageService.send(embedBuilder, channel);
                } else {
                    EmbedBuilder embedBuilder = new EmbedBuilder().setTitle("Output too long, attaching as file");
                    embedBuilder.setColor(ColorSchemeProperty.getColor());
                    byte[] bytes = resultString.getBytes();

                    if (bytes.length > 1000000) {
                        messageService.sendError("Output too long", channel);
                        return;
                    }

                    ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                    messageService.executeMessageDispatchAction(channel, c -> {
                        MessageCreateBuilder messageCreateBuilder = new MessageCreateBuilder()
                            .addEmbeds(embedBuilder.build())
                            .addFiles(FileUpload.fromData(inputStream, "output.txt"));

                        return c.sendMessage(messageCreateBuilder.build());
                    });
                }
            }
        } catch (TimeoutException e) {
            messageService.sendError(e.getMessage(), channel);
        } catch (ExecutionException e) {
            Throwable error = e.getCause() != null ? e.getCause() : e;

            if (error instanceof SecurityException) {
                messageService.sendError(error.getMessage(), channel);
                throw new CommandFailure(error);
            } else if (error instanceof MultipleCompilationErrorsException) {
                MultipleCompilationErrorsException compilationErrorsException = (MultipleCompilationErrorsException) error;
                EmbedBuilder embedBuilder = new EmbedBuilder();
                embedBuilder.setColor(Color.RED);
                embedBuilder.setTitle("Could not compile script");
                embedBuilder.setDescription(ExceptionUtils.formatScriptCompilationError(compilationErrorsException));
                messageService.sendTemporary(embedBuilder.build(), channel);
                throw new CommandFailure(error);
            } else if (!(error instanceof CommandFailure)) {
                EmbedBuilder embedBuilder = ExceptionUtils.buildErrorEmbed(error);
                embedBuilder.setTitle("Error occurred while executing script");
                messageService.sendTemporary(embedBuilder.build(), channel);
                throw new CommandFailure(error);
            }
        }
    }

    private <T> Future<T> scriptExecution(Callable<T> execution) {
        return GLOBAL_POOL.submit(() -> {
            try {
                return execution.call();
            } finally {
                groovyWhitelistManager.resetInvocationCounts();
            }
        });
    }

    private <T> T runScriptWithTimeout(Future<T> result, long timeout, TimeUnit timeUnit) throws ExecutionException, TimeoutException {
        try {
            if (isPrivileged) {
                return result.get();
            } else {
                return result.get(timeout, timeUnit);
            }
        } catch (CancellationException e) {
            return null;
        } catch (InterruptedException e) {
            result.cancel(true);
            return null;
        } catch (TimeoutException e) {
            result.cancel(true);
            throw new TimeoutException("Script execution timed out");
        }
    }

    private GroovyShell createShell() {
        GroovyShell groovyShell;

        if (isPrivileged) {
            User user = context.getUser();
            if (!securityManager.isAdmin(user)) {
                throw new SecurityException(String.format("Cannot set up privileged shell for user %s, only allowed for admin users.", user.getAsMention()));
            }
            groovyShell = new GroovyShell(groovySandboxComponent.getPrivilegedCompilerConfiguration());
        } else {
            groovyShell = new GroovyShell(groovySandboxComponent.getCompilerConfiguration());
        }

        groovyVariableManager.prepareShell(groovyShell);

        return groovyShell;
    }

}
