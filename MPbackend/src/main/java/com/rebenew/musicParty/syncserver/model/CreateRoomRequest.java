package com.rebenew.musicParty.syncserver.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class CreateRoomRequest {
    // Getters y Setters
    private String senderId;

    public CreateRoomRequest() {}

    public CreateRoomRequest(String senderId) {
        this.senderId = senderId;
    }

}