package com.rebenew.musicParty.syncserver.model;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO para actualizar configuración de sala
 */

@Getter
@Setter
public class RoomSettingRequest {
    private String senderId;
    private Boolean allowGuestsAddTracks;
    private Boolean allowGuestsControl;

    public RoomSettingRequest() {}

}
