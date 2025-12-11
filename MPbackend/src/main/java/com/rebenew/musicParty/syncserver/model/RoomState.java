package com.rebenew.musicParty.syncserver.model;

//Estados posibles de una sala de música sincronizada.

public enum RoomState {
    CREATED,           // Sala creada pero host no confirmado
    ACTIVE,            // Host activo y reproduciendo
    PAUSED,            // Host pausado
    HOST_DISCONNECTED, // Host desconectado - esperando reconexión
    TERMINATED         // Sala eliminada
}