package com.rebenew.musicParty.syncserver.model;

/**
 * Representa una canción en la playlist de una sala.
 * Añadimos durationMs para soportar finalización automática.
 */
public record TrackEntry(
        String trackId,
        String title,
        String addedBy,
        long addedAt,
        long durationMs   // duración en milisegundos, 0 = desconocida
) {
    public TrackEntry {
        if (trackId == null || trackId.trim().isEmpty()) {
            throw new IllegalArgumentException("trackId no puede ser nulo o vacío");
        }
        if (addedBy == null || addedBy.trim().isEmpty()) {
            throw new IllegalArgumentException("addedBy no puede ser nulo o vacío");
        }
        if (addedAt <= 0) {
            addedAt = System.currentTimeMillis();
        }
        if (durationMs < 0) {
            durationMs = 0L; // no permitimos negativos
        }
    }

    // Constructor auxiliar retrocompatible (sin duración conocida)
    public TrackEntry(String trackId, String title, String addedBy, long addedAt) {
        this(trackId, title, addedBy, addedAt, 0L);
    }

    // Métodos de compatibilidad (opcionales, el record ya genera accessors)
    public String getTrackId() { return trackId; }
    public String getTitle() { return title; }
    public String getAddedBy() { return addedBy; }
    public long getAddedAt() { return addedAt; }
    public long getDurationMs() { return durationMs; }
}
