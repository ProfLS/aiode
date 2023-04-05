package net.robinfriedli.aiode.command.argument;

import java.util.Map;
import java.util.function.Function;

import net.robinfriedli.aiode.audio.AudioManager;
import net.robinfriedli.aiode.audio.queue.AudioQueue;
import org.apache.commons.collections4.map.CaseInsensitiveMap;

import groovy.lang.GroovyShell;
import net.robinfriedli.aiode.Aiode;
import net.robinfriedli.aiode.command.AbstractCommand;
import net.robinfriedli.aiode.command.CommandContext;
import net.robinfriedli.aiode.discord.property.properties.ArgumentPrefixProperty;
import net.robinfriedli.aiode.discord.property.properties.PrefixProperty;
import net.robinfriedli.aiode.entities.xml.CommandContribution;
import net.robinfriedli.aiode.exceptions.InvalidArgumentException;
import net.robinfriedli.aiode.exceptions.InvalidCommandException;
import net.robinfriedli.aiode.exceptions.UnexpectedCommandSetupException;
import net.robinfriedli.jxp.api.StringConverter;
import net.robinfriedli.jxp.api.XmlElement;
import net.robinfriedli.jxp.exceptions.ConversionException;

/**
 * Manages arguments in the context of one command execution. Each AbstractCommand instance receives it's own instance
 * of this class. This class manages what arguments were used with what assigned value (e.g. $select=5) when invoking
 * this command and provides general access to arguments available to this command across it's entire hierarchy.
 */
public class ArgumentController {

    private final AbstractCommand sourceCommand;
    private final CommandContribution commandContribution;
    private final Map<String, ArgumentUsage> usedArguments;
    private final GroovyShell groovyShell;

    CommandContext context;

    private boolean shellInitialised;

    public ArgumentController(AbstractCommand sourceCommand) {
        this.sourceCommand = sourceCommand;
        commandContribution = sourceCommand.getCommandContribution();
        usedArguments = new CaseInsensitiveMap<>();
        groovyShell = new GroovyShell();
    }

    /**
     * @return the arguments used when calling this command mapped to the {@link ArgumentUsage}
     */
    public Map<String, ArgumentUsage> getUsedArguments() {
        return usedArguments;
    }

    /**
     * Get a defined argument definition for this command or any of its super classes or null.
     *
     * @param arg the identifier of the argument, case insensitive
     * @return the found argument definition
     */
    public ArgumentDefinition get(String arg) {
        return commandContribution.getArgument(arg);
    }

    /**
     * Get an argument that was used when calling this command by its identifier
     *
     * @param arg the argument identifier
     * @return the {@link ArgumentUsage} instance.
     */
    public ArgumentUsage getUsedArgument(String arg) {
        return usedArguments.get(arg);
    }

    /**
     * same as {@link #get(String)} but throws an exception if no argument definition was found
     */
    public ArgumentDefinition require(String arg) {
        return require(arg, InvalidArgumentException::new);
    }

    public ArgumentDefinition require(String arg, Function<String, ? extends RuntimeException> exceptionProducer) {
        ArgumentDefinition argument = get(arg);

        if (argument == null) {
            throw exceptionProducer.apply(String.format("Undefined argument '%s' on command '%s'.", arg, sourceCommand.getIdentifier()));
        }

        return argument;
    }

    /**
     * @return true if the user used the provided argument when calling the command
     */
    public boolean argumentSet(String argument) {
        return usedArguments.containsKey(argument);
    }

    /**
     * Add an argument used by the user to the setArguments when processing the command, verifying whether that argument
     * exists and meets all defined rules happens when {@link #verify()} is called.
     */
    public void setArgument(String argument) {
        setArgument(argument, "");
    }

    /**
     * Same as {@link #setArgument(String)} but assigns the given value provided by the user
     */
    public void setArgument(String argument, String value) {
        ArgumentDefinition arg = require(argument);
        usedArguments.put(argument, new ArgumentUsage(arg, value));
    }

    public boolean hasArguments() {
        return !getArguments().isEmpty();
    }

    /**
     * Verifies all set argument rules
     */
    public void verify() throws InvalidCommandException {
        for (ArgumentUsage value : usedArguments.values()) {
            value.verify();
        }
    }

    /**
     * @return all arguments that may be used with this command
     */
    public Map<String, CommandArgument> getArguments() {
        return commandContribution.getArguments();
    }

    /**
     * @return the source command this ArgumentContribution was built for
     */
    public AbstractCommand getSourceCommand() {
        return sourceCommand;
    }

    /**
     * Copy state from an existing ArgumentContribution instance onto this one. Useful when forking commands. The
     * ArgumentContribution must be from the same Command type.
     *
     * @param argumentController the old argument controller
     */
    public void transferValues(ArgumentController argumentController) {
        Class<? extends AbstractCommand> currentCommandType = sourceCommand.getClass();
        Class<? extends AbstractCommand> providedCommandType = argumentController.getSourceCommand().getClass();
        if (!currentCommandType.equals(providedCommandType)) {
            throw new IllegalArgumentException(
                String.format("Provided argumentContribution is of a different Command type. Current type: %s; Provided type:%s",
                    currentCommandType, providedCommandType)
            );
        }

        for (Map.Entry<String, ArgumentUsage> usageEntry : argumentController.getUsedArguments().entrySet()) {
            usedArguments.put(usageEntry.getKey(), usageEntry.getValue());
        }
    }

    /**
     * Describes a single argument used in this command invocation, referencing the persistent argument description and
     * the value assigned when invoking the command, e.g. $select=5
     */
    public class ArgumentUsage {

        private final ArgumentDefinition argument;
        private final String value;

        public ArgumentUsage(ArgumentDefinition argument, String value) {
            this.argument = argument;
            this.value = value;
        }

        public ArgumentDefinition getArgument() {
            return argument;
        }

        public String getValue() {
            return value;
        }

        /**
         * @return the value assigned to this argument cast to the given type
         */
        public <E> E getValue(Class<E> type) {
            try {
                return StringConverter.convert(value, type);
            } catch (ConversionException e) {
                throw new InvalidCommandException(String.format("Invalid argument value. Cannot convert '%s' to type %s",
                    value, type.getSimpleName()));
            }
        }

        /**
         * @return true if this argument has been assigned a value. This is false for most arguments as most arguments
         * follow a set or not set principle without requiring a value.
         */
        public boolean hasValue() {
            return !value.isEmpty();
        }

        public void verify() throws InvalidCommandException {
            for (XmlElement excludedArgument : argument.getExcludedArguments()) {
                String excludedArgumentIdentifier = excludedArgument.getAttribute("argument").getValue();
                if (argumentSet(excludedArgumentIdentifier)) {
                    if (excludedArgument.hasAttribute("message")) {
                        throw new InvalidCommandException(excludedArgument.getAttribute("message").getValue());
                    } else {
                        throw new InvalidCommandException(String.format("Argument '%s' can not be set if '%s' is set.",
                            this.argument.getIdentifier(), excludedArgumentIdentifier));
                    }
                }
            }

            for (XmlElement requiredArgument : argument.getRequiredArguments()) {
                String requiredArgumentIdentifier = requiredArgument.getAttribute("argument").getValue();
                if (!argumentSet(requiredArgumentIdentifier)) {
                    if (requiredArgument.hasAttribute("message")) {
                        throw new InvalidCommandException(requiredArgument.getAttribute("message").getValue());
                    } else {
                        throw new InvalidCommandException(String.format("Argument '%s' may only be set if argument '%s' is set.",
                            this.argument.getIdentifier(), requiredArgumentIdentifier));
                    }
                }
            }

            if (argument.requiresValue() && !hasValue()) {
                throw new InvalidCommandException("Argument " + argument.getIdentifier()
                    + " requires an assigned value. E.g. $argument=value or $argument=\"val ue\". "
                    + "Commands are parsed in the following manner: `command name $arg1 $arg2=arg2val $arg3=\"arg3 value\" input $arg4 arg4 value $arg5 arg5 value`.");
            }

            if (argument.requiresInput() && getSourceCommand().getCommandInput().isBlank()) {
                throw new InvalidCommandException("Argument " + argument.getIdentifier() + " requires additional command input.");
            }

            groovyShell.setVariable("value", hasValue() ? getValue(argument.getValueType()) : value);

            for (XmlElement rule : argument.getRules()) {
                String condition = rule.getTextContent();
                if (!evaluateScript(condition)) {
                    String prefix = PrefixProperty.getEffectiveCommandStartForCurrentContext();
                    char argumentPrefix = ArgumentPrefixProperty.getForCurrentContext().getArgumentPrefix();
                    throw new InvalidCommandException(String.format(rule.getAttribute("errorMessage").getValue(), prefix, argumentPrefix, value));
                }
            }

            if (hasValue()) {
                for (XmlElement valueCheck : argument.getValueChecks()) {
                    String check = valueCheck.getAttribute("check").getValue();
                    Aiode aiode = Aiode.get();
                    AudioManager audioManager = aiode.getAudioManager();
                    AudioQueue queue = audioManager.getQueue(context.getGuild());
                    Integer queueSize = queue.getSize();
                    if (!evaluateScript(check)) {
                        String prefix = PrefixProperty.getEffectiveCommandStartForCurrentContext();
                        char argumentPrefix = ArgumentPrefixProperty.getForCurrentContext().getArgumentPrefix();
                        throw new InvalidCommandException(String.format(valueCheck.getAttribute("errorMessage").getValue(), prefix, argumentPrefix, queueSize));
                    }
                }
            }
        }

        private boolean evaluateScript(String script) {
            try {
                if (!shellInitialised) {
                    Aiode.get().getGroovyVariableManager().prepareShell(groovyShell);
                    // make sure the command variable is set to the source command is it might differ from the command of
                    // the current execution context if a command verifies another command, e.g. the PresetCommand
                    groovyShell.setVariable("command", sourceCommand);
                    shellInitialised = true;
                }

                return (boolean) groovyShell.evaluate(script);
            } catch (ClassCastException e) {
                throw new UnexpectedCommandSetupException(String.format("Groovy script for argument '%s' does not return boolean", argument.getIdentifier()), e);
            } catch (Exception e) {
                throw new UnexpectedCommandSetupException(String.format("Groovy script for argument '%s' threw an exception", argument.getIdentifier()), e);
            }
        }

    }

}
