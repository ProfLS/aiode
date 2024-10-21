package net.robinfriedli.aiode.discord.property.properties;

import net.robinfriedli.aiode.discord.property.AbstractGuildProperty;
import net.robinfriedli.aiode.entities.GuildSpecification;
import net.robinfriedli.aiode.entities.xml.GuildPropertyContribution;

/**
 * Property that forces the bot to avoid music videos
 */
public class DefaultSourceProperty extends AbstractGuildProperty {

    public AudioOnly(GuildPropertyContribution contribution) {
        super(contribution);
    }

    @Override
    protected void setBoolValue(boolean bool, GuildSpecification guildSpecification) {
        guildSpecification.setAudioOnly(bool);
    }

    @Override
    public Object extractPersistedValue(GuildSpecification guildSpecification) {
        return guildSpecification.isAudioOnly();
    }
}
