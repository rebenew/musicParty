package com.rebenew.musicParty.syncserver.model;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;
/**
 * DTO para respuestas API - solo datos necesarios para el frontend
 */

@Getter
@Setter
public class RoomResponse {
    private String roomId;
    private String hostSenderId;
    private boolean allowGuestsAddTracks;
    private Set<String> clients;
    private int playlistSize;

    public RoomResponse(String roomId, String hostSenderId, boolean allowGuestsAddTracks, Set<String> clients, int playlistSize) {
        this.roomId = roomId;
        this.hostSenderId = hostSenderId;
        this.allowGuestsAddTracks = allowGuestsAddTracks;
        this.clients = clients;
        this.playlistSize = playlistSize;
    }
}
