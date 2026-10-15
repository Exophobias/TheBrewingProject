package dev.jsinco.brewery.bukkit.command;

import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.List;
import java.util.ArrayList;

/** Permission-filtered documentation; execution remains guarded by the command tree. */
public final class BreweryHelp {
    private BreweryHelp() {}
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> attach(
            com.mojang.brigadier.builder.LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> root) {
        return root.executes(context -> show(context.getSource().getSender(), "1"))
                .then(io.papermc.paper.command.brigadier.Commands.literal("help")
                        .executes(context -> show(context.getSource().getSender(), "1"))
                        .then(io.papermc.paper.command.brigadier.Commands.argument("page", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    if (hasStaff(context.getSource().getSender())
                                            && "staff".startsWith(builder.getRemainingLowerCase())) builder.suggest("staff");
                                    return builder.buildFuture();
                                })
                                .executes(context -> show(context.getSource().getSender(), context.getArgument("page", String.class)))))
                .then(io.papermc.paper.command.brigadier.Commands.argument("unknown", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(context -> {
                            context.getSource().getSender().sendMessage(Component.text("Unknown or incomplete command.", NamedTextColor.RED));
                            return show(context.getSource().getSender(), "1");
                        }));
    }
    private record Entry(String form, String description, String permission, boolean staff, boolean others) {}
    private static final List<Entry> ENTRIES = List.of(
            new Entry("version", "show plugin version", "version", false, false),
            new Entry("info [slot]", "inspect a brew", "info", true, true),
            new Entry("create <steps>", "create a staff test brew", "create", true, true),
            new Entry("seal", "seal inventory brews", "seal", true, true),
            new Entry("brewer <add|remove|set|clear>", "edit brew maker attribution", "brewer", true, true),
            new Entry("status <info|clear|set|consume>", "inspect or edit drunkenness", "status", true, true),
            new Entry("reload", "reload operator configuration", "reload", true, false),
            new Entry("event <event>", "trigger a drunken event", "event", true, true),
            new Entry("replicate <recipe> <quality>", "create a known recipe for testing", "replicate", true, true),
            new Entry("encryption rotate_key [reencrypt]", "rotate the brew encryption key", "encryption", true, false),
            new Entry("debug [slot]", "inspect raw brew diagnostics", "debug", true, true),
            new Entry("dump [args]", "write a diagnostic dump", "dump", true, false));
    private static List<Entry> visible(CommandSender sender, boolean staff) {
        return ENTRIES.stream().filter(entry -> entry.staff() == staff
                && sender.hasPermission("brewery.command." + entry.permission())).toList();
    }
    public static boolean hasStaff(CommandSender sender) { return !visible(sender, true).isEmpty(); }
    public static int show(CommandSender sender, String requested) {
        List<Entry> ordinary = visible(sender, false);
        List<Entry> staff = visible(sender, true);
        boolean directStaff = requested.equalsIgnoreCase("staff");
        if ((ordinary.isEmpty() && staff.isEmpty()) || (directStaff && staff.isEmpty())) {
            sender.sendMessage(Component.text("You do not have permission to use these commands.", NamedTextColor.RED));
            return 0;
        }
        int ordinaryPages = (ordinary.size() + 7) / 8;
        int total = ordinaryPages + (staff.size() + 7) / 8;
        int page = 1;
        try { page = Integer.parseInt(requested); } catch (NumberFormatException ignored) { }
        page = Math.max(1, Math.min(total, directStaff ? ordinaryPages + 1 : page));
        boolean staffPage = page > ordinaryPages;
        List<Entry> section = staffPage ? staff : ordinary;
        int offset = ((staffPage ? page - ordinaryPages : page) - 1) * 8;
        Component header = Component.text("  ---------- ", NamedTextColor.DARK_GRAY)
                .append(Component.text("TheBrewingProject" + (staffPage ? " Staff" : ""), NamedTextColor.GREEN));
        if (total > 1) header = header.append(Component.text(" (", NamedTextColor.DARK_GRAY))
                .append(Component.text(page, NamedTextColor.WHITE)).append(Component.text("/", NamedTextColor.DARK_GRAY))
                .append(Component.text(total, NamedTextColor.WHITE)).append(Component.text(")", NamedTextColor.DARK_GRAY));
        sender.sendMessage(header.append(Component.text(" ----------", NamedTextColor.DARK_GRAY)));
        for (Entry entry : section.subList(offset, Math.min(section.size(), offset + 8))) {
            String form = entry.form();
            if (entry.others() && sender.hasPermission("brewery.command.other")) form += " [for <player>]";
            sender.sendMessage(Component.text("/tbp " + form, NamedTextColor.GREEN)
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(entry.description(), NamedTextColor.GRAY)));
        }
        sender.sendMessage(page < total ? Component.text("  ---------- ", NamedTextColor.DARK_GRAY)
                .append(Component.text("/tbp help " + (page + 1), NamedTextColor.GREEN))
                .append(Component.text(" for more", NamedTextColor.GRAY)).append(Component.text(" ----------", NamedTextColor.DARK_GRAY))
                : Component.text("  ----------------------------------------", NamedTextColor.DARK_GRAY));
        return 1;
    }
}
