import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { Subscription, map } from 'rxjs';
import { AsyncPipe, CommonModule, NgIf } from '@angular/common';
import { SyncOrchestratorService } from '../../services/sync-orchestrator.service';
import { StateService } from '../../services/state.service';
import { MessageHandlerService } from '../../services/message-handler.service';

@Component({
  standalone: true,
  imports: [AsyncPipe, NgIf, CommonModule], // 
  selector: 'app-sync-player',
  templateUrl: './sync-player.component.html',
  styleUrls: ['./sync-player.component.scss']
})
export class SyncPlayerComponent implements OnInit, OnDestroy {
  // ✅ INYECCIÓN CORREGIDA: Usar inject() para mejor testabilidad
  private syncOrchestrator = inject(SyncOrchestratorService);
  private state = inject(StateService);
  private messageHandler = inject(MessageHandlerService);
  
  private subs = new Subscription();

  // ✅ ELIMINADO: Estado duplicado - usar StateService
  // isPlaying, roomId, senderId, isConnected, isHost, allowGuestsAddTracks, playlist

  // ✅ Observables para la plantilla
  roomId$ = this.state.roomId$;
  isConnected$ = this.state.isConnected$;
  isHost$ = this.state.state$.pipe(map(s => s.isHost));
  allowGuestsAddTracks$ = this.state.state$.pipe(map(s => s.allowGuestsAddTracks));
  playlist$ = this.state.playlist$;
  currentTrack$ = this.state.currentTrack$;
  
  // ✅ Estado local mínimo necesario para UI
  senderId: string | null = null;
  isPlaying = false;

  ngOnInit(): void {
    this.initializeUser();
    this.setupSubscriptions();
    this.attemptAutoConnect();
  }

  /**
   * Inicializa identificadores de usuario
   */
  private initializeUser(): void {
    this.senderId = localStorage.getItem('senderId') ?? crypto.randomUUID();
    localStorage.setItem('senderId', this.senderId);
  }

  /**
   * Configura todas las suscripciones
   */
  private setupSubscriptions(): void {
    // ✅ SUSCRIPCIÓN CORREGIDA: Usar MessageHandlerService para mensajes de playback
    this.subs.add(
      this.messageHandler.playbackMessages.subscribe(msg => {
        if (msg.senderId === this.senderId) return; // Ignorar mensajes propios
        
        const action = String(msg.action ?? '').toLowerCase();
        if (action === 'play') this.isPlaying = true;
        else if (action === 'pause') this.isPlaying = false;
        // 'seek' se maneja automáticamente a través del StateService
      })
    );

    // ✅ SUSCRIPCIÓN MEJORADA: Sincronizar estado de reproducción
    this.subs.add(
      this.state.currentTrack$.subscribe(track => {
        this.isPlaying = track?.isPlaying ?? false;
      })
    );

    // ✅ SUSCRIPCIÓN: Manejar errores de conexión
    this.subs.add(
      this.syncOrchestrator.errors$.subscribe(error => {
        console.warn('Error de sincronización:', error);
        // Podrías mostrar notificaciones al usuario aquí
      })
    );
  }

  /**
   * Intenta conexión automática si hay roomId persistido
   */
  private attemptAutoConnect(): void {
    const savedRoomId = localStorage.getItem('roomId');
    if (savedRoomId && this.senderId) {
      this.syncOrchestrator.start(savedRoomId, this.senderId)
        .catch(err => console.warn('Conexión automática fallida:', err));
    }
  }

  /**
   * Crear nueva sala como host
   */
  async createRoom(): Promise<void> {
    if (!this.senderId) return;
    
    try {
      const result = await this.syncOrchestrator.hostCreateAndConnect(this.senderId);
      localStorage.setItem('roomId', result.roomId);
      console.log('Sala creada:', result.roomId);
    } catch (error) {
      console.error('Error creando sala:', error);
    }
  }

  /**
   * Unirse a sala existente
   */
  async joinRoom(roomId: string): Promise<void> {
    if (!roomId || !this.senderId) return;
    
    try {
      await this.syncOrchestrator.guestJoin(roomId, this.senderId);
      localStorage.setItem('roomId', roomId);
      console.log('Unido a sala:', roomId);
    } catch (error) {
      console.error('Error uniéndose a sala:', error);
    }
  }

  /**
   * Alternar reproducción/pausa
   */
  togglePlay(): void {
    this.state.snapshot.roomId ?? console.warn('No hay roomId.');
    
    const action = this.isPlaying ? 'pause' : 'play';
    this.syncOrchestrator.sendAction(action)
      .then(success => {
        if (!success) {
          console.warn('No se pudo enviar acción de reproducción');
          // Revertir estado local si falla
          this.isPlaying = !this.isPlaying;
        }
      });
  }

  /**
   * Añadir track a la playlist
   */
  async onAddTrack(trackId: string, title: string): Promise<void> {
    if (!trackId || !title) {
      alert('Ingresa trackId y título');
      return;
    }

    if (!this.canAddTrack()) {
      alert('No tienes permiso para añadir canciones');
      return;
    }

    const ok = await this.syncOrchestrator.sendAddTrack(trackId, title);
    if (!ok) {
      alert('No se pudo añadir el track');
    }
  }

  /**
   * Actualizar configuración de la sala (solo host)
   */
  onToggleAllowGuestsAddTracks(e: Event): void {
    const checked = (e.target as HTMLInputElement).checked;
    
    // ✅ VERIFICACIÓN MEJORADA: Usar StateService para verificar permisos
    if (!this.state.snapshot.isHost) {
      console.warn('Solo el host puede cambiar la configuración');
      return;
    }

    this.syncOrchestrator.sendHostSettings(true, checked)
      .then(success => {
        if (!success) {
          alert('No se pudo actualizar configuración');
          // Revertir el checkbox si falla
          (e.target as HTMLInputElement).checked = !checked;
        }
      });
  }

  /**
   * Verificar permisos para añadir tracks
   */
  canAddTrack(): boolean {
    return this.state.canUserAddTracks();
  }

  /**
   * Verificar permisos para controlar reproducción
   */
  canControlPlayback(): boolean {
    return this.state.canUserControlPlayback();
  }

  /**
   * Abandonar sala
   */
  leaveRoom(): void {
    this.syncOrchestrator.stop();
    localStorage.removeItem('roomId');
  }

  /**
   * Actualizar playlist manualmente
   */
  refreshPlaylist(): void {
    this.syncOrchestrator.refreshPlaylist()
      .catch(err => console.warn('Error actualizando playlist:', err));
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
    // ✅ DECISIÓN CONSCIENTE: No cerrar conexión para mantener background
    // this.leaveRoom(); // Descomentar si quieres cerrar al salir del componente
  }
}