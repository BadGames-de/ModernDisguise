package dev.iiahmed.disguise;

import org.bukkit.entity.EntityType;

import java.util.UUID;

public final class PlayerInfo {

    private final String name, nickname;
    private final UUID uuid, nickUUID;
    private final Skin skin;
    private final Entity entity;

    PlayerInfo(
            final String name,
            final String nickname,
            final UUID uuid,
            final UUID nickUUID,
            final Skin skin,
            final Entity entity
    ) {
        if (name == null) {
            throw new IllegalArgumentException("Input real name can't be null.");
        }
        if (uuid == null) {
            throw new IllegalArgumentException("Input real uuid can't be null.");
        }
        this.name = name;
        this.nickname = nickname;
        this.uuid = uuid;
        this.nickUUID = nickUUID;
        this.skin = skin;
        this.entity = entity;
    }

    /**
     * @return the real name of the disguised Player
     */
    public String getName() {
        return name;
    }

    /**
     * @return the fake name of the disguised Player, will return
     * the real one if there's no fake
     */
    public String getNickname() {
        return nickname == null ? name : nickname;
    }

    /**
     * @return the real uuid of the disguised Player
     */
    public UUID getUUID() { return uuid; }

    /**
     * @return the fake uuid of the disguised Player, will return
     * the real one if there's no fake
     */
    public UUID getNickUUID() { return nickUUID == null ? uuid : nickUUID; }

    /**
     * @return the skin info of the player
     */
    public Skin getSkin() {
        return skin;
    }

    /**
     * @return the {@link Entity} of the disguised Player
     */
    public Entity getEntity() {
        return entity;
    }

    /**
     * @return the {@link EntityType} of the disguised Player
     */
    public EntityType getEntityType() {
        return entity == null? EntityType.PLAYER : entity.getType();
    }

    /**
     * @return a {@link Boolean} that indicates whether the disguise had changed the player's name
     */
    public boolean hasName() {
        return nickname != null && !nickname.isEmpty() && !nickname.equals(name);
    }

    /**
     * @return a {@link Boolean} that indicates whether the disguise had changed the player's skin
     */
    public boolean hasSkin() {
        return skin != null && skin.isValid();
    }

    /**
     * @return a {@link Boolean} that indicates whether the disguise had changed the player's entity
     */
    public boolean hasEntity() {
        return entity != null && entity.isValid();
    }

    /**
     * @return a {@link Boolean} that indicates whether the disguise had changed the player's uuid
     */
    public boolean hasUUID() {
        return nickUUID != null && !nickUUID.equals(uuid);
    }

}
