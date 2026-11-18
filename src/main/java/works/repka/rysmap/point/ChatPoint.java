package works.repka.rysmap.point;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.jetbrains.annotations.Nullable;
import works.repka.rysmap.hud.Minimap;
import works.repka.rysmap.screen.MapScreen;

/**
 * Точка в чате (макет {@code 09b}, этап М7): кнопка «В чат» пишет строку {@code [Точка: Шахта · 1 388 · 64 · −434 ·
 * Верхний мир]}. Игрок без RYSMap видит просто текст. У игрока с RYSMap строка становится подчёркнутой ссылкой
 * «Клик: добавить точку себе»: клик зовёт клиентскую команду {@code /rysmap point}, она открывает окно новой точки с
 * этими данными. Слово «Точка» берётся на любом из двух языков, измерение по имени (иначе текущее).
 */
public final class ChatPoint {
    static final Pattern PATTERN = ChatText.PATTERN;

    private ChatPoint() {}

    /** Строка для чата. */
    public static String text(PointStore.Point p) {
        return "[" + Component.translatable("rysmap.chat.point").getString() + ": " + PointsLayer.name(p) + " · " + Minimap.group(p.x) + " · "
            + Minimap.group(p.y) + " · " + Minimap.group(p.z) + " · " + MapScreen.dimensionName(p.dimension) + "]";
    }

    /** Отправить точку в чат от имени игрока. */
    public static void send(PointStore.Point p) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.connection.sendChat(text(p));
    }

    public record Parsed(String name, int x, int y, int z, @Nullable String dimension) {}

    public static @Nullable Parsed parse(Matcher m) {
        ChatText.Parsed p = ChatText.parse(m);
        return p == null ? null : new Parsed(p.name(), p.x(), p.y(), p.z(), p.dimension());
    }

    /** Входящее сообщение: найденные точки становятся ссылками. */
    public static void onChat(ClientChatReceivedEvent e) {
        Component msg = e.getMessage();
        String plain = msg.getString();
        Matcher m = PATTERN.matcher(plain);
        if (!m.find()) return;
        MutableComponent out = Component.empty();
        int last = 0;
        m.reset();
        while (m.find()) {
            Parsed p = parse(m);
            if (p == null) continue;
            out.append(plain.substring(last, m.start()));
            String dim = dimensionId(p.dimension());
            String cmd = "/rysmap point " + p.x() + " " + p.y() + " " + p.z() + " " + StringArgumentType.escapeIfRequired(dim) + " " + p.name();
            out.append(Component.literal(m.group()).withStyle(Style.EMPTY.withUnderlined(true).withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("rysmap.chat.add")))));
            last = m.end();
        }
        out.append(plain.substring(last));
        // текст сообщения целиком в одну строку: цвета ника и префиксов сервера уходят, ссылки важнее
        e.setMessage(out);
    }

    /** id измерения по видимому имени; неизвестное: текущее измерение игрока. */
    static String dimensionId(@Nullable String name) {
        var mc = Minecraft.getInstance();
        String here = mc.level == null ? "minecraft:overworld" : mc.level.dimension().location().toString();
        if (name == null || mc.getConnection() == null) return here;
        for (var key : mc.getConnection().levels()) {
            String id = key.location().toString();
            if (MapScreen.dimensionName(id).equalsIgnoreCase(name) || id.equalsIgnoreCase(name)) return id;
        }
        return here;
    }

    public static void register(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("rysmap").then(Commands.literal("point")
            .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
                .then(Commands.argument("z", IntegerArgumentType.integer()).then(Commands.argument("dimension", StringArgumentType.string())
                    .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                        int x = IntegerArgumentType.getInteger(c, "x"), y = IntegerArgumentType.getInteger(c, "y"), z = IntegerArgumentType.getInteger(c, "z");
                        String dim = StringArgumentType.getString(c, "dimension"), name = StringArgumentType.getString(c, "name");
                        String n = name;
                        // окно открывается после закрытия чата
                        Minecraft.getInstance().tell(() -> works.repka.rysmap.screen.QuickPointScreen.openAt(dim, x, y, z, n));
                        return 1;
                    }))))))));
    }
}
