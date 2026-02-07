package dev.iiahmed.disguise.listener;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.iiahmed.disguise.DisguiseManager;
import dev.iiahmed.disguise.DisguiseProvider;
import dev.iiahmed.disguise.PlayerInfo;
import dev.iiahmed.disguise.Skin;
import dev.iiahmed.disguise.util.DisguiseUtil;
import dev.iiahmed.disguise.util.Version;
import dev.iiahmed.disguise.util.reflection.FieldAccessor;
import dev.iiahmed.disguise.util.reflection.Reflections;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class PacketListener extends ChannelDuplexHandler {

    private static final String BUNDLE_PACKET_NAME = "ClientboundBundlePacket";
    private static final String PACKET_NAME;
    private static final FieldAccessor<?> PACKET_LIST;
    private static final FieldAccessor<UUID> PLAYER_ID;

    // Player Info Update packet fields (for 1.21.10+ disguise fix)
    private static final String PLAYER_INFO_PACKET_NAME = "ClientboundPlayerInfoUpdatePacket";
    private static final Class<?> PLAYER_INFO_PACKET_CLASS;
    private static final Class<?> PLAYER_INFO_ENTRY_CLASS;
    private static final FieldAccessor<?> PLAYER_INFO_ENTRIES;
    private static final FieldAccessor<?> PLAYER_INFO_ACTIONS;
    private static final Constructor<?> PLAYER_INFO_ENTRY_CONSTRUCTOR;
    private static final Constructor<?> PLAYER_INFO_PACKET_CONSTRUCTOR;
    private static final boolean PLAYER_INFO_SUPPORTED;

    static {
        try {
            final String prefix = Version.isOrOver(17) ? "net.minecraft.network.protocol.game." : DisguiseUtil.PREFIX;
            final Class<?> namedEntitySpawn = Reflections.findClass(
                    // order is IMPORTANT
                    prefix + "PacketPlayOutNamedEntitySpawn",
                    prefix + "PacketPlayOutSpawnEntity",
                    prefix + "ClientboundAddEntityPacket"
            ).orElseThrow(() -> new RuntimeException("Failed to find spawn entity packet class!"));
            PACKET_NAME = namedEntitySpawn.getSimpleName();

            PLAYER_ID = Reflections.getField(namedEntitySpawn, UUID.class);
            if (Version.isOrOver(20)) {
                PACKET_LIST = Reflections.getField(Class.forName("net.minecraft.network.protocol.BundlePacket"), Iterable.class);
            } else {
                PACKET_LIST = null;
            }

            // Initialize PlayerInfoUpdatePacket handling for 1.21.10+ fix
            boolean playerInfoSupported = false;
            Class<?> playerInfoPacketClass = null;
            Class<?> playerInfoEntryClass = null;
            FieldAccessor<?> playerInfoEntries = null;
            FieldAccessor<?> playerInfoActions = null;
            Constructor<?> playerInfoEntryConstructor = null;
            Constructor<?> playerInfoPacketConstructor = null;

            if (Version.isOrOver(19)) {
                try {
                    playerInfoPacketClass = Class.forName(prefix + "ClientboundPlayerInfoUpdatePacket");

                    // Find the Entry inner class
                    for (Class<?> innerClass : playerInfoPacketClass.getDeclaredClasses()) {
                        if (innerClass.getSimpleName().equals("Entry")) {
                            playerInfoEntryClass = innerClass;
                            break;
                        }
                    }

                    if (playerInfoEntryClass != null) {
                        // Get entries field (List<Entry>)
                        playerInfoEntries = Reflections.getField(playerInfoPacketClass, List.class);
                        // Get actions field (EnumSet<Action>)
                        playerInfoActions = Reflections.getField(playerInfoPacketClass, EnumSet.class);

                        // Find Entry constructor - typically (UUID, GameProfile, boolean, int, GameMode, Component, RemoteChatSession.Data)
                        // We'll use reflection to find the right constructor
                        for (Constructor<?> ctor : playerInfoEntryClass.getDeclaredConstructors()) {
                            Class<?>[] paramTypes = ctor.getParameterTypes();
                            if (paramTypes.length >= 4 && paramTypes[0] == UUID.class && paramTypes[1] == GameProfile.class) {
                                playerInfoEntryConstructor = ctor;
                                playerInfoEntryConstructor.setAccessible(true);
                                break;
                            }
                        }

                        // Find packet constructor that takes EnumSet and List
                        for (Constructor<?> ctor : playerInfoPacketClass.getDeclaredConstructors()) {
                            Class<?>[] paramTypes = ctor.getParameterTypes();
                            if (paramTypes.length == 2 && paramTypes[0] == EnumSet.class && paramTypes[1] == List.class) {
                                playerInfoPacketConstructor = ctor;
                                playerInfoPacketConstructor.setAccessible(true);
                                break;
                            }
                        }

                        playerInfoSupported = playerInfoEntryConstructor != null && playerInfoPacketConstructor != null;
                    }
                } catch (final Exception e) {
                    Bukkit.getLogger().log(Level.WARNING, "[ModernDisguise] Failed to initialize PlayerInfoUpdatePacket handling", e);
                }
            }

            PLAYER_INFO_PACKET_CLASS = playerInfoPacketClass;
            PLAYER_INFO_ENTRY_CLASS = playerInfoEntryClass;
            PLAYER_INFO_ENTRIES = playerInfoEntries;
            PLAYER_INFO_ACTIONS = playerInfoActions;
            PLAYER_INFO_ENTRY_CONSTRUCTOR = playerInfoEntryConstructor;
            PLAYER_INFO_PACKET_CONSTRUCTOR = playerInfoPacketConstructor;
            PLAYER_INFO_SUPPORTED = playerInfoSupported;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final DisguiseProvider provider = DisguiseManager.getProvider();
    private final Player player;

    public PacketListener(Player player) {
        this.player = player;
    }

    @Override
    public void write(
            final ChannelHandlerContext context,
            final Object packet,
            final ChannelPromise promise
    ) throws Exception {
        if (packet == null) {
            super.write(context, null, promise);
            return;
        }

        final String name = packet.getClass().getSimpleName();
        if (PACKET_NAME.equals(name)) {
            this.handleSpawnPacket(context, packet, packet, promise);
            return;
        } else if (PLAYER_INFO_PACKET_NAME.equals(name) && PLAYER_INFO_SUPPORTED) {
            this.handlePlayerInfoPacket(context, packet, promise);
            return;
        } else if (BUNDLE_PACKET_NAME.equals(name)) {
            assert PACKET_LIST != null;
            final Iterable<?> iterable = (Iterable<?>) PACKET_LIST.get(packet);
            for (final Object bundlePacket : iterable) {
                final String packetName = bundlePacket.getClass().getSimpleName();
                if (packetName.equals(PACKET_NAME)) {
                    this.handleSpawnPacket(context, bundlePacket, packet, promise);
                    return;
                }
            }
        }

        super.write(context, packet, promise);
    }

    private void handleSpawnPacket(
            final ChannelHandlerContext context,
            final Object spawnPacket,
            final Object passPacket,
            final ChannelPromise promise
    ) throws Exception {
        UUID playerID;
        try {
            playerID = PLAYER_ID.get(spawnPacket);
        } catch (final Exception exception) {
            provider.getPlugin().getLogger().log(
                    Level.SEVERE,
                    "[ModernDisguise] Couldn't get a player's UUID, please report if this ever happens to you.\n"
                    + "Version: " + Version.NMS + " (" + Version.VERSION_EXACT + ")\n"
                    + "Packet Name: " + PACKET_NAME + "\n"
                    + "This error is not supposed to happen however it is harmless & won't block any packet from being sent.",
                    exception
            );
            playerID = null;
        }

        if (playerID == null) {
            super.write(context, passPacket, promise);
            return;
        }

        final Player refreshed = Bukkit.getPlayer(playerID);
        if (refreshed != null && provider.isDisguisedAsEntity(refreshed)) {
            provider.refreshAsEntity(refreshed, false, player);
            return;
        }

        super.write(context, passPacket, promise);
    }

    /**
     * Handles ClientboundPlayerInfoUpdatePacket to modify GameProfile for
     * disguised players. This is the core fix for 1.21.10+ where server-side
     * GameProfile modification no longer works.
     */
    private void handlePlayerInfoPacket(
            final ChannelHandlerContext context,
            final Object packet,
            final ChannelPromise promise
    ) throws Exception {
        try {
            @SuppressWarnings("unchecked")
            final List<Object> entries = (List<Object>) PLAYER_INFO_ENTRIES.get(packet);
            @SuppressWarnings("unchecked")
            final EnumSet<?> actions = (EnumSet<?>) PLAYER_INFO_ACTIONS.get(packet);

            if (entries == null || entries.isEmpty()) {
                super.write(context, packet, promise);
                return;
            }

            boolean needsModification = false;
            final List<Object> modifiedEntries = new ArrayList<>(entries.size());

            for (final Object entry : entries) {
                // Extract fields from entry using reflection
                UUID entryUuid = null;
                GameProfile entryProfile = null;

                for (java.lang.reflect.Field field : entry.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    if (field.getType() == UUID.class) {
                        entryUuid = (UUID) field.get(entry);
                    } else if (field.getType() == GameProfile.class) {
                        entryProfile = (GameProfile) field.get(entry);
                    }
                }

                if (entryUuid == null) {
                    modifiedEntries.add(entry);
                    continue;
                }

                // Check if this player is disguised
                final Player disguisedPlayer = Bukkit.getPlayer(entryUuid);
                if (disguisedPlayer == null || !provider.isDisguised(disguisedPlayer)) {
                    modifiedEntries.add(entry);
                    continue;
                }

                final PlayerInfo info = provider.getInfo(disguisedPlayer);

                // Check if we need to modify name or skin
                if (!info.hasName() && !info.hasSkin()) {
                    modifiedEntries.add(entry);
                    continue;
                }

                needsModification = true;

                // Create a modified GameProfile
                final String newName = info.hasName() ? info.getNickname() : (entryProfile != null ? entryProfile.getName() : disguisedPlayer.getName());
                final GameProfile modifiedProfile = new GameProfile(entryUuid, newName);

                // Copy skin properties
                if (info.hasSkin()) {
                    final Skin skin = info.getSkin();
                    modifiedProfile.getProperties().put("textures", new Property("textures", skin.getTextures(), skin.getSignature()));
                } else if (entryProfile != null) {
                    // Copy existing properties
                    modifiedProfile.getProperties().putAll(entryProfile.getProperties());
                }

                // Create a new entry with the modified profile
                // We need to extract other fields from the original entry
                final Object modifiedEntry = createModifiedEntry(entry, entryUuid, modifiedProfile);
                if (modifiedEntry != null) {
                    modifiedEntries.add(modifiedEntry);
                } else {
                    modifiedEntries.add(entry);
                }
            }

            if (!needsModification) {
                super.write(context, packet, promise);
                return;
            }

            // Create a new packet with the modified entries
            final Object modifiedPacket = PLAYER_INFO_PACKET_CONSTRUCTOR.newInstance(actions, modifiedEntries);
            super.write(context, modifiedPacket, promise);

        } catch (final Exception exception) {
            provider.getPlugin().getLogger().log(
                    Level.WARNING,
                    "[ModernDisguise] Failed to modify PlayerInfoUpdatePacket, sending original packet.\n"
                    + "Version: " + Version.NMS + " (" + Version.VERSION_EXACT + ")",
                    exception
            );
            super.write(context, packet, promise);
        }
    }

    /**
     * Creates a modified Entry with the new GameProfile while preserving other
     * fields.
     */
    private Object createModifiedEntry(final Object originalEntry, final UUID uuid, final GameProfile modifiedProfile) {
        try {
            // Extract all fields from original entry
            final java.lang.reflect.Field[] fields = originalEntry.getClass().getDeclaredFields();
            final Object[] constructorArgs = new Object[PLAYER_INFO_ENTRY_CONSTRUCTOR.getParameterCount()];
            final Class<?>[] paramTypes = PLAYER_INFO_ENTRY_CONSTRUCTOR.getParameterTypes();

            for (int i = 0; i < paramTypes.length; i++) {
                if (paramTypes[i] == UUID.class) {
                    constructorArgs[i] = uuid;
                } else if (paramTypes[i] == GameProfile.class) {
                    constructorArgs[i] = modifiedProfile;
                } else {
                    // Find the matching field in the original entry
                    for (java.lang.reflect.Field field : fields) {
                        field.setAccessible(true);
                        if (paramTypes[i].isAssignableFrom(field.getType())) {
                            constructorArgs[i] = field.get(originalEntry);
                            break;
                        }
                    }
                }
            }

            return PLAYER_INFO_ENTRY_CONSTRUCTOR.newInstance(constructorArgs);
        } catch (final Exception e) {
            return null;
        }
    }

}
