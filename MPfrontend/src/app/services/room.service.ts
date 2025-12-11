import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';

// Este es el metadata del RoomResponse en el backend
export type RoomMetadata = {
  roomId: string;
  hostSenderId: string | null;
  allowGuestsEditQueue: boolean;  
  clients: string[]; // Lista de senderIds conectados
  playlistSize: number;
};

// Representa una entrada de pista en la lista de reproducción
 
export type TrackEntry = {
  trackId: string;
  title: string;
  addedBy: string;
  // addedAt fue omitido temporalmente, puede servir para una notificación popup futura
};

// Request para crear una sala
export type CreateRoomReq = { senderId: string };

// Response al crear una sala
export type CreateRoomRes = { roomId: string; shortId?: string };

/**
 * Servicio para manejar salas y sus configs via API HTTP
 * 
 * Proporciona métodos para:
 * - Creación y borrar de salas 
 * - Obtención de metadata de salas
 * - Gestión de listas de reproducción
 * - Actualización de configuraciones de salas
 */

@Injectable({ providedIn: 'root' })
export class RoomService {
  private readonly baseUrl = 'http://localhost:8080';
  private readonly jsonHeaders = new HttpHeaders({ 'Content-Type': 'application/json' });
  private readonly defaultTimeout = 8000;

  constructor(private http: HttpClient) {}

  /*
   * Crea una nueva sala en el backend
   * @param senderId - ID del usuario que crea la sala
   * @returns Promise con roomId 
   * @throws Error si falta senderId o falla la petición
   */
  async createRoom(senderId: string): Promise<CreateRoomRes> {
    if (!senderId) throw new Error('senderId required');
    const body: CreateRoomReq = { senderId };
    try {
      const obs = this.http.post<CreateRoomRes>(
        `${this.baseUrl}/rooms/create`, 
        body, 
        { headers: this.jsonHeaders }
      );
      return await firstValueFrom(obs.pipe(timeout(this.defaultTimeout)));
    } catch (err) {
      throw RoomService.toError(err, 'createRoom failed');
    }
  }

  /*
   * Obtiene metadata de una sala
   * @param roomId - ID de la sala
   * @returns Promise con room metadata incluyendo clientes y configuraciones
   * @throws Error si falta roomId o falla la petición
   */

  async getRoom(roomId: string): Promise<RoomMetadata> {
    if (!roomId) throw new Error('roomId required');
    try {
      const obs = this.http.get<RoomMetadata>(
        `${this.baseUrl}/rooms/${encodeURIComponent(roomId)}`
      );
      return await firstValueFrom(obs.pipe(timeout(this.defaultTimeout)));
    } catch (err) {
      throw RoomService.toError(err, 'getRoom failed');
    }
  }

  /*
   * Obtiene la lista de reproducción de una sala
   * @param roomId - ID de la sala
   * @returns Promise con array de TrackEntry
   * @throws Error si falta roomId o falla la petición
   */

  async getPlaylist(roomId: string): Promise<TrackEntry[]> {
    if (!roomId) throw new Error('roomId required');
    try {
      const obs = this.http.get<TrackEntry[]>(
        `${this.baseUrl}/rooms/${encodeURIComponent(roomId)}/playlist`
      );
      return await firstValueFrom(obs.pipe(timeout(this.defaultTimeout)));
    } catch (err) {
      throw RoomService.toError(err, 'getPlaylist failed');
    }
  }

  /*
   * Actualiza configuraciones de la sala (host only)
   * 
   * Sends RoomSettingRequest to backend with senderId and permission flags
   * Backend expects allowGuestsAddTracks and allowGuestsControl in request
   * 
   * @param roomId - ID of the room to update
   * @param senderId - ID of the user making the request (must be host)
   * @param options - Settings to update
   * @param options.allowGuestsAddTracks - Whether guests can add tracks to playlist
   * @param options.allowGuestsControl - Whether guests can control playback
   * @returns Promise resolving when update completes
   * @throws Error if roomId or senderId missing, or request fails
   */

  async updateSettings(
    roomId: string,
    senderId: string,
    options: { 
      allowGuestsAddTracks?: boolean; 
      allowGuestsControl?: boolean; 
    }
  ): Promise<any> {
    if (!roomId || !senderId) throw new Error('roomId and senderId required');

    const allowGuestsAddTracks = options.allowGuestsAddTracks ?? false;
    const allowGuestsControl = options.allowGuestsControl ?? false;

    const body = { senderId, allowGuestsAddTracks, allowGuestsControl };
    try {
      const obs = this.http.post(
        `${this.baseUrl}/rooms/${encodeURIComponent(roomId)}/settings`, 
        body, 
        { headers: this.jsonHeaders }
      );
      return await firstValueFrom(obs.pipe(timeout(this.defaultTimeout)));
    } catch (err) {
      throw RoomService.toError(err, 'updateSettings failed');
    }
  }

  /*
   * Elimina una sala (solo host)
   * @param roomId - ID of the room to delete
   * @param senderId - ID of the user making the request (must be host)
   * @returns Promise resolving when deletion completes
   * @throws Error if roomId or senderId missing, or request fails
   */

  async deleteRoom(roomId: string, senderId: string): Promise<any> {
    if (!roomId || !senderId) throw new Error('roomId and senderId required');
    const body = { senderId };
    try {
      const obs = this.http.request(
        'DELETE', 
        `${this.baseUrl}/rooms/${encodeURIComponent(roomId)}`, 
        { body, headers: this.jsonHeaders }
      );
      return await firstValueFrom(obs.pipe(timeout(this.defaultTimeout)));
    } catch (err) {
      throw RoomService.toError(err, 'deleteRoom failed');
    }
  }

  /*
   * Convierte errores HTTP o genéricos en objetos Error con mensajes descriptivos
   * @param err - Original error (HttpErrorResponse, Error, or unknown)
   * @param context - Context description for the error
   * @returns Formatted Error object with descriptive message
   */
  private static toError(err: unknown, context = ''): Error {
    if (err instanceof HttpErrorResponse) {
      const payload = err.error && typeof err.error === 'object' 
        ? JSON.stringify(err.error) 
        : String(err.error || '');
      const msg = `${context}: HTTP ${err.status} ${err.statusText} ${payload}`;
      return new Error(msg);
    }
    if (err instanceof Error) return new Error(`${context}: ${err.message}`);
    return new Error(`${context}: unknown error`);
  }
}