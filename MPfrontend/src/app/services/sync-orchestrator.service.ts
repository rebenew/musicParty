import { Injectable, OnDestroy } from '@angular/core';
import { Subject, Subscription } from 'rxjs';
import { WebsocketService } from './websocket.service';
import { RoomService } from './room.service';
import { StateService } from './state.service';

/**
 * Servicio orquestador principal - Coordina todos los servicios de sincronización
 * Expone API pública para componentes y maneja el flujo de alto nivel
 */
@Injectable({ providedIn: 'root' })
export class SyncOrchestratorService implements OnDestroy {
  private roomId?: string;
  private senderId?: string;
  private usingBackground = false;
  
  private connectionStateSubject = new Subject<'connected' | 'connecting' | 'disconnected'>();
  public connectionState$ = this.connectionStateSubject.asObservable();
  
  private errorsSubject = new Subject<string>();
  public errors$ = this.errorsSubject.asObservable();

  // Subscripciones
  private wsMessageSub?: Subscription;

  private readonly TIMEOUTS = {
    CONNECTION: 8000,
    ACTION: 4000,
    ADD_TRACK: 6000
  };

  constructor(
    private wsService: WebsocketService,
    private roomApi: RoomService,
    private state: StateService
  ) {
    this.setupMessageHandling();
  }

  /**
   * Inicia la conexión de sincronización
   */
  async start(roomId: string, senderId: string): Promise<void> {
    const normalized = SyncOrchestratorService.normalizeRoomId(roomId);
    if (!normalized || !senderId) {
      throw new Error('Se requieren roomId y senderId');
    }

    // Idempotencia
    if (this.roomId === normalized && this.senderId === senderId) {
      return;
    }

    this.connectionStateSubject.next('connecting');
    
    try {
      this.roomId = normalized;
      this.senderId = senderId;

      await this.startWithWebSocket(normalized, senderId);
      this.usingBackground = false;


      this.connectionStateSubject.next('connected');
      this.state.patch({ roomId: normalized, senderId, isConnected: true });
      
    } catch (error: any) {
      this.connectionStateSubject.next('disconnected');
      this.errorsSubject.next(`Error de conexión: ${error.message}`);
      throw error;
    }
  }

  /**
   * Crea sala y se conecta como host
   */
  async hostCreateAndConnect(hostSenderId: string): Promise<{ roomId: string }> {
    try {
      const created = await this.roomApi.createRoom(hostSenderId);
      const roomId = created.roomId;
      
      if (!roomId) {
        throw new Error('El servidor no devolvió un roomId');
      }

      await this.start(roomId, hostSenderId);
      await this.refreshPlaylist();
      
      // Configurar permisos por defecto
      await this.roomApi.updateSettings(roomId, hostSenderId, {
        allowGuestsControl: true,
        allowGuestsAddTracks: true
      });
      
      this.state.patch({ isHost: true });
      
      return { roomId };
      
    } catch (error: any) {
      this.errorsSubject.next(`Error creando sala: ${error.message}`);
      throw error;
    }
  }

  /**
   * Reconectar a sala existente como host
   */
  async hostRejoin(roomId: string, senderId: string): Promise<void> {
    try {
      // Verificar si la sala aún existe
      try {
        await this.roomApi.getRoom(roomId);
      } catch {
        // Si no existe, lanza error para que la UI decida si crear una nueva
        throw new Error('La sala ya no existe');
      }

      await this.start(roomId, senderId);
      await this.refreshPlaylist();
      this.state.patch({ isHost: true });
      
    } catch (error: any) {
      this.errorsSubject.next(`Error reconectando: ${error.message}`);
      throw error;
    }
  }

  /**
   * Unirse a sala como invitado
   */
  async guestJoin(roomId: string, guestSenderId: string): Promise<void> {
    try {
      await this.roomApi.getRoom(roomId);
      await this.start(roomId, guestSenderId);
      await this.refreshPlaylist();
      
      this.state.patch({ isHost: false });
      
    } catch (error: any) {
      this.errorsSubject.next(`Error uniéndose a sala: ${error.message}`);
      throw error;
    }
  }

  /**
   * Envía acción de reproducción
   */
  async sendAction(action: string, position?: number): Promise<boolean> {
    if (!this.roomId || !this.senderId) {
      this.errorsSubject.next('No hay sala activa');
      return false;
    }

    if (!this.state.snapshot.isHost && !this.state.snapshot.allowGuestsControl) {
      this.errorsSubject.next('No tienes permisos para controlar la reproducción');
      return false;
    }

    try {
      const msg: any = {
        type: 'playback',
        subType: action,
        roomId: this.roomId,
        senderId: this.senderId,
        data: { positionMs: position || 0 },
        timestamp: Date.now()
      };

      return this.sendPayload(msg, true, this.TIMEOUTS.ACTION);
      
    } catch (error: any) {
      this.errorsSubject.next(`Error enviando acción: ${error.message}`);
      return false;
    }
  }

  /**
   * Envía solicitud para añadir track
   */
  async sendAddTrack(trackId: string, title: string): Promise<boolean> {
    if (!this.roomId || !this.senderId) {
      this.errorsSubject.next('No hay sala activa');
      return false;
    }

    if (!this.state.snapshot.isHost && !this.state.snapshot.allowGuestsAddTracks) {
      this.errorsSubject.next('No tienes permisos para agregar tracks');
      return false;
    }

    const msg = {
      type: 'playlist',
      subType: 'add',
      roomId: this.roomId,
      senderId: this.senderId,
      data: { 
        trackId, 
        title, 
        addedBy: this.senderId,
        addedAt: Date.now()
      },
      timestamp: Date.now()
    };

    return this.sendPayload(msg, true, this.TIMEOUTS.ADD_TRACK);
  }

  /**
   * Envía configuración de host
   */
  async sendHostSettings(allowGuestsControl: boolean, allowGuestsAddTracks: boolean): Promise<boolean> {
    if (!this.roomId || !this.senderId) {
      this.errorsSubject.next('No hay sala activa');
      return false;
    }

    if (!(await this.isCurrentUserHost())) {
      this.errorsSubject.next('Solo el host puede cambiar la configuración');
      return false;
    }

    try {
      await this.roomApi.updateSettings(this.roomId, this.senderId, {
        allowGuestsControl,
        allowGuestsAddTracks
      });

      this.state.patch({
        allowGuestsControl,
        allowGuestsAddTracks
      });

      return true;
    } catch (error: any) {
      this.errorsSubject.next(`Error actualizando configuración: ${error.message}`);
      return false;
    }
  }

  /**
   * Actualiza la playlist desde el backend
   */
  async refreshPlaylist(): Promise<any[]> {
    if (!this.roomId) return [];
    try {
      const list = await this.roomApi.getPlaylist(this.roomId);
      this.state.setPlaylist(list);
      return list;
    } catch (e) {
      console.warn('Error actualizando playlist:', e);
      return [];
    }
  }

  /**
   * Detiene y limpia recursos
   */
  stop(): void {
    this.wsService.disconnect();

    this.cleanupSubscriptions();

    this.connectionStateSubject.next('disconnected');
    this.state.patch({ 
      isConnected: false,
      roomId: null,
      senderId: null,
      isHost: false
    });
    this.roomId = undefined;
    this.senderId = undefined;
    this.usingBackground = false;
  }

  ngOnDestroy(): void {
    this.stop();
    this.connectionStateSubject.complete();
    this.errorsSubject.complete();
  }

  // --- Métodos privados ---

  private async startWithWebSocket(roomId: string, senderId: string): Promise<void> {
    this.wsService.connect(roomId, senderId);

    await new Promise<void>((resolve, reject) => {
      const sub = this.wsService.status.subscribe(s => {
        if (s === 'OPEN') {
          sub.unsubscribe();
          resolve();
        } else if (s === 'ERROR' || s === 'CLOSED') {
          sub.unsubscribe();
          reject(new Error(`WebSocket ${s.toLowerCase()}`));
        }
      });

      setTimeout(() => {
        sub.unsubscribe();
        reject(new Error('Timeout de conexión WebSocket'));
      }, this.TIMEOUTS.CONNECTION);
    });

    // Enviar mensaje de autenticación
    await this.sendPayload({
      type: 'auth',
      roomId: roomId,
      senderId: senderId,
      data: { isHost: this.state.snapshot.isHost }
    }, true);
  }

  private async sendPayload(payload: any, waitAck = true, timeoutMs = 4000): Promise<boolean> {
    if (!payload) return false;
    
    if (!payload.roomId && this.roomId) payload.roomId = this.roomId;
    if (!payload.senderId && this.senderId) payload.senderId = this.senderId;
    if (!payload.timestamp) payload.timestamp = Date.now();

    if (waitAck) {
      try {
        const ack = await this.wsService.sendWithAck(payload, timeoutMs);
        return Boolean(ack && ack.success);
      } catch (e) {
        return false;
      }
    } else {
      this.wsService.sendObject(payload);
      return true;
    }
  }

  private async isCurrentUserHost(): Promise<boolean> {
    if (!this.roomId) return false;
    
    try {
      const room = await this.roomApi.getRoom(this.roomId);
      return room.hostSenderId === this.senderId;
    } catch {
      return false;
    }
  }

  private cleanupSubscriptions(): void {
    this.wsMessageSub?.unsubscribe();
  }

  private setupMessageHandling(): void {
    this.wsMessageSub = this.wsService.messages.subscribe(msg => {
      this.handleIncomingMessage(msg);
    });
  }

  private handleIncomingMessage(msg: any): void {
    switch (msg.type) {
      case 'roomState':
        this.state.patch({
          isHost: msg.data.isHost,
          allowGuestsAddTracks: msg.data.allowGuestsAddTracks,
          allowGuestsControl: msg.data.allowGuestsControl,
          playlist: msg.data.playlist,
          users: msg.data.users
        });
        break;
      case 'playback':
        this.state.updatePlaybackState(msg.data.isPlaying, msg.data.positionMs);
        break;
      case 'playlist':
        if (msg.subType === 'update') {
          this.state.setPlaylist(msg.data.playlist);
        }
        break;
      case 'userUpdate':
        if (msg.subType === 'join') {
          this.state.addUser(msg.data.senderId);
        } else if (msg.subType === 'leave') {
          this.state.removeUser(msg.data.senderId);
        }
        break;
    }
  }

  private static normalizeRoomId(raw?: string | null): string | null {
    if (!raw) return null;
    return raw.replace(/^[{<"]|[}>"]$/g, '').trim();
  }
}