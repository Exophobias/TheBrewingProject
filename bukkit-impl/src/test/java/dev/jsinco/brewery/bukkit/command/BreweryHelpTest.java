package dev.jsinco.brewery.bukkit.command;

import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class BreweryHelpTest {
    private static String plain(Component component) {
        return (component instanceof TextComponent text ? text.content() : "")
                + component.children().stream().map(BreweryHelpTest::plain).collect(java.util.stream.Collectors.joining());
    }
    private static CommandSourceStack source(Set<String> grants, List<String> output) {
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) return grants.contains(args[0].toString());
                    if (method.getName().equals("sendMessage") && args[0] instanceof Component component) { output.add(plain(component)); return null; }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        return (CommandSourceStack) Proxy.newProxyInstance(CommandSourceStack.class.getClassLoader(),
                new Class<?>[]{CommandSourceStack.class}, (proxy, method, args) -> method.getName().equals("getSender") ? sender : null);
    }
    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(BreweryHelp.attach(Commands.literal("tbp")));
        return dispatcher;
    }
    @Test void actualHelpHandlersHideStaffFromOrdinaryViewersAndDenyDirectStaffNavigation() throws Exception {
        var dispatcher = dispatcher();
        var output = new ArrayList<String>();
        var source = source(Set.of("brewery.command.version"), output);
        dispatcher.execute("tbp", source);
        assertEquals(3, output.size());
        assertTrue(output.get(1).contains("/tbp version"));
        assertFalse(output.get(0).contains("Staff"));
        assertFalse(output.get(2).contains("for more"));
        assertTrue(dispatcher.getCompletionSuggestions(dispatcher.parse("tbp help ", source)).get().getList().isEmpty());
        output.clear();
        dispatcher.execute("tbp help staff", source);
        assertEquals(1, output.size());
        assertTrue(output.getFirst().contains("permission"));
        output.clear();
        dispatcher.execute("tbp nonsense", source);
        assertTrue(output.getFirst().contains("Unknown"));
        assertTrue(output.get(2).contains("version"));
    }
    @Test void partialStaffPagesAndOtherPlayerSyntaxAreFilteredIndependently() throws Exception {
        var dispatcher = dispatcher();
        var output = new ArrayList<String>();
        var source = source(Set.of("brewery.command.version", "brewery.command.reload", "brewery.command.info"), output);
        dispatcher.execute("tbp help 99", source);
        assertTrue(output.getFirst().contains("Staff"));
        assertTrue(String.join("\n", output).contains("reload"));
        assertFalse(String.join("\n", output).contains("for <player>"));
        assertFalse(String.join("\n", output).contains("debug"));
        assertFalse(output.getLast().contains("for more"));
        assertTrue(dispatcher.getCompletionSuggestions(dispatcher.parse("tbp help s", source)).get().getList().stream().anyMatch(value -> value.getText().equals("staff")));
        output.clear();
        dispatcher.execute("tbp help banana", source);
        assertFalse(output.getFirst().contains("Staff"));
    }
    @Test void staffOnlyStartsWithoutEmptyPlayerPageAndPaginatesEightEntries() throws Exception {
        Set<String> grants = new HashSet<>();
        for (String command : List.of("info", "create", "seal", "brewer", "status", "reload", "event", "replicate", "encryption", "debug", "dump", "other")) grants.add("brewery.command." + command);
        var output = new ArrayList<String>();
        var source = source(grants, output);
        var dispatcher = dispatcher();
        dispatcher.execute("tbp help 0", source);
        assertEquals(10, output.size());
        assertTrue(output.getFirst().contains("Staff"));
        assertTrue(String.join("\n", output).contains("for <player>"));
        output.clear();
        dispatcher.execute("tbp help 99", source);
        assertEquals(5, output.size());
        assertTrue(String.join("\n", output).contains("dump"));
        assertFalse(output.getLast().contains("for more"));
    }
}
