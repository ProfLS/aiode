package net.robinfriedli.aiode.command.widget.widgets;

import java.util.List;
import java.util.Optional;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.robinfriedli.aiode.command.PermissionTarget;
import net.robinfriedli.aiode.command.SecurityManager;
import net.robinfriedli.aiode.command.widget.EmbedTablePaginationWidget;
import net.robinfriedli.aiode.command.widget.WidgetRegistry;
import net.robinfriedli.aiode.entities.AccessConfiguration;
import net.robinfriedli.aiode.util.EmbedTable;
import net.robinfriedli.stringlist.StringList;
import org.jetbrains.annotations.Nullable;

public class PermissionListPaginationWidget extends EmbedTablePaginationWidget<PermissionTarget> {

    private final SecurityManager securityManager;

    public PermissionListPaginationWidget(WidgetRegistry widgetRegistry, Guild guild, MessageChannel channel, List<PermissionTarget> elements, SecurityManager securityManager) {
        super(widgetRegistry, guild, channel, elements, 20);
        this.securityManager = securityManager;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected EmbedTablePaginationWidget.Column<PermissionTarget>[] getColumns() {
        return new EmbedTablePaginationWidget.Column[]{
            new Column<PermissionTarget>("Permission", permissionTarget -> {
                int childTargetLevel = 0;
                PermissionTarget currentParent = permissionTarget.getParentTarget();
                while (currentParent != null) {
                    childTargetLevel++;
                    currentParent = currentParent.getParentTarget();
                }

                if (childTargetLevel == 0) {
                    return permissionTarget.getPermissionTargetIdentifier();
                } else {
                    return "-".repeat(childTargetLevel) + " *" + permissionTarget.getPermissionTargetIdentifier() + "*";
                }
            }, permissionTarget -> {
                PermissionTarget rootTarget = permissionTarget;
                PermissionTarget parent;
                while ((parent = rootTarget.getParentTarget()) != null) {
                    rootTarget = parent;
                }

                PermissionTarget.PermissionTypeCategory permissionTypeCategory = rootTarget.getPermissionTypeCategory();
                int categorySorting = permissionTypeCategory.getSorting();
                return EmbedTable.Group.namedGroupOrdered(
                    "***" + permissionTypeCategory.getCategoryName() + "***",
                    permissionTypeCategory, categorySorting
                );
            }),
            new Column<PermissionTarget>("Available to", permissionTarget -> {
                Optional<AccessConfiguration> accessConfiguration = securityManager.getAccessConfiguration(permissionTarget, getGuild().getIdString());
                if (accessConfiguration.isPresent()) {
                    List<Role> roles = accessConfiguration.get().getRoles(getGuild().get());
                    if (roles.isEmpty()) {
                        return "Guild owner and administrator roles only";
                    } else {
                        return StringList.create(roles, Role::getName).toSeparatedString(", ");
                    }
                } else {
                    return "Available to everyone";
                }
            }, permissionTarget -> {
                PermissionTarget rootTarget = permissionTarget;
                PermissionTarget parent;
                while ((parent = rootTarget.getParentTarget()) != null) {
                    rootTarget = parent;
                }

                PermissionTarget.PermissionTypeCategory permissionTypeCategory = rootTarget.getPermissionTypeCategory();
                int categorySorting = permissionTypeCategory.getSorting();
                return EmbedTable.Group.silentGroupOrdered(
                    "***" + permissionTypeCategory.getCategoryName() + "***",
                    permissionTypeCategory,
                    categorySorting
                );
            })
        };
    }

    @Override
    protected String getTitle() {
        return "Permissions";
    }

    @Nullable
    @Override
    protected String getDescription() {
        return "Lists all set permissions for all permission targets. Arguments are only listed if there is a restriction set up for them.";
    }
}
