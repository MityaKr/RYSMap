package works.repka.rysmap.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import works.repka.rysmap.places.PlaceBook;
import works.repka.rysmap.places.PlaceNames;

/**
 * Команды мест (план Essentials 2 и 4.1): те же имена, что у FTB Essentials, старые {@code /listhomes}, {@code /listwarps}
 * псевдонимами. Пока FTB Essentials стоит, корневые имена его, наши только {@code /rys place …} (раздел 6). Имя места
 * принимается словом или в кавычках ({@code /warp "boos apoth"}), подсказка берёт в кавычки то, что без них не читается.
 */
public final class PlaceCommands {
    private PlaceCommands() {}

    /** Команда: имя и как построить её узел под этим именем. */
    private record Spec(String name, Function<String, LiteralArgumentBuilder<CommandSourceStack>> node, String... aliases) {}

    static void register(CommandDispatcher<CommandSourceStack> d, boolean ftb) {
        Spec[] specs = {
            new Spec("home", n -> named(n, false, Places::home, PlaceCommands::homes)),
            new Spec("sethome", n -> named(n, false, (p, name) -> Places.setHome(p, name, null), PlaceCommands::homes)),
            new Spec("delhome", n -> named(n, false, Places::delHome, PlaceCommands::homes)),
            new Spec("homes", n -> simple(n, Places::listHomes), "listhomes"),
            new Spec("teamhome", n -> simple(n, Places::teamHome)),
            new Spec("setteamhome", n -> simple(n, p -> Places.setTeamHome(p, null))),
            new Spec("delteamhome", n -> simple(n, Places::delTeamHome)),
            new Spec("warp", n -> named(n, true, Places::warp, PlaceCommands::warps)),
            new Spec("warps", n -> simple(n, Places::listWarps), "listwarps"),
            new Spec("setwarp", n -> named(n, true, (p, name) -> Places.setWarp(p, name, null, null), PlaceCommands::warps)),
            new Spec("delwarp", n -> named(n, true, Places::delWarp, PlaceCommands::warps)),
            new Spec("back", n -> simple(n, p -> Places.back(p, 0))),
            new Spec("spawn", n -> simple(n, Places::spawn)),
            new Spec("playerspawn", n -> simple(n, Places::bed)),
            new Spec("rtp", n -> simple(n, Places::random)),
            new Spec("tpa", n -> target(n, true, (p, t) -> Requests.send(p, t, false))),
            new Spec("tpahere", n -> target(n, true, (p, t) -> Requests.send(p, t, true))),
            new Spec("tpaccept", n -> target(n, false, (p, t) -> Requests.answer(p, t, true))),
            new Spec("tpdeny", n -> target(n, false, (p, t) -> Requests.answer(p, t, false))),
            new Spec("tpacancel", n -> target(n, false, Requests::cancel)),
        };
        LiteralArgumentBuilder<CommandSourceStack> place = Commands.literal("place");
        for (Spec s : specs) {
            place.then(s.node.apply(s.name));
            if (!ftb) {
                d.register(s.node.apply(s.name));
                for (String a : s.aliases) d.register(s.node.apply(a));
            }
        }
        d.register(Commands.literal("rys").then(place));
    }

    private static int reply(CommandContext<CommandSourceStack> c, Places.Reply r) {
        if (r == null) return 0;
        if (!r.text().getString().isEmpty()) {
            if (r.ok()) c.getSource().sendSuccess(r::text, false);
            else c.getSource().sendFailure(r.text());
        }
        return r.ok() ? 1 : 0;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> simple(String name, Function<ServerPlayer, Places.Reply> run) {
        return Commands.literal(name).requires(CommandSourceStack::isPlayer).executes(c -> reply(c, run.apply(c.getSource().getPlayerOrException())));
    }

    /** Команда с именем места: необязательным (дом) или обязательным (место сервера). */
    private static LiteralArgumentBuilder<CommandSourceStack> named(String name, boolean required, BiFunction<ServerPlayer, String, Places.Reply> run,
                                                                    SuggestionProvider<CommandSourceStack> suggest) {
        var node = Commands.literal(name).requires(CommandSourceStack::isPlayer)
            .then(Commands.argument("name", StringArgumentType.string()).suggests(suggest)
                .executes(c -> reply(c, run.apply(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
        if (!required) node.executes(c -> reply(c, run.apply(c.getSource().getPlayerOrException(), "")));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> target(String name, boolean required, BiFunction<ServerPlayer, ServerPlayer, Places.Reply> run) {
        var node = Commands.literal(name).requires(CommandSourceStack::isPlayer)
            .then(Commands.argument("player", EntityArgument.player())
                .executes(c -> reply(c, run.apply(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player")))));
        if (!required) node.executes(c -> reply(c, run.apply(c.getSource().getPlayerOrException(), null)));
        return node;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> homes(CommandContext<CommandSourceStack> c,
                                                                                                         com.mojang.brigadier.suggestion.SuggestionsBuilder b)
        throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        return SharedSuggestionProvider.suggest(PlaceStore.get(p.server).book().homes(p.getUUID()).stream().map(h -> PlaceNames.quote(h.name)), b);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> warps(CommandContext<CommandSourceStack> c,
                                                                                                         com.mojang.brigadier.suggestion.SuggestionsBuilder b)
        throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        return SharedSuggestionProvider.suggest(PlaceStore.get(p.server).book().places().stream().filter(w -> Places.sees(p, w))
            .map((PlaceBook.Place w) -> PlaceNames.quote(w.name)), b);
    }
}
