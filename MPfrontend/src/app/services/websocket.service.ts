import { Injectable, NgZone } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/**
 * WebsocketService - Servicio para comunicación WebSocket con el backend Spring Boot
 * 
 * CARACTERÍSTICAS:
 * - Conexión automática y reconexión
 * - Sistema de ACK con correlationId estándar
 * - Heartbeat para mantener conexión activa
 * - Cola de mensajes para envío cuando no hay conexión
 * 
 * ENDPOINT: ws://localhost:8080/ws/music-sync (debe coincidir con @ServerEndpoint del backend)
 */

export interface SyncMsg {
    type: string;
    subType?: string;
    roomId?: string;
    senderId?: string;
    correlationId?: string;
    timestamp?: number;
    data?: any; // Changed from payload to data to match backend
    success?: boolean;
    reason?: string;
}

@Injectable({ providedIn: 'root' })
export class WebsocketService {
  private socket?: WebSocket;
  private messages$ = new Subject<SyncMsg>();
  private status$ = new Subject<'CONNECTING' | 'OPEN' | 'CLOSED' | 'ERROR'>();
  private sendQueue: SyncMsg[] = [];
  private readonly maxSendQueue = 200;
  private reconnectAttempts = 0;
  private reconnectTimerSub?: any;
  private heartbeatTimerId?: number;
  private ackMap = new Map<string, { resolve: (v: SyncMsg) => void, reject: (e: any) => void, timeoutId: number }>();
  private baseUrl = 'ws://localhost:8080/ws/music-sync'; 
  private currentRoom?: string;
  private currentSender?: string;
  private readonly ackTimeoutMs = 8000;

  constructor(private ngZone: NgZone) {}

  /**
   * Conecta al WebSocket del backend Spring Boot
   */
  connect(roomId: string, senderId: string): void {
    const normalizedRoom = WebsocketService.normalizeRoomId(roomId);
    if (!normalizedRoom || !senderId) throw new Error('roomId and senderId required');
    this.currentRoom = normalizedRoom;
    this.currentSender = senderId;

    // ✅ Idempotencia: evitar reconexiones innecesarias
    if (this.socket && this.socket.readyState === WebSocket.OPEN &&
        this.currentRoom === normalizedRoom && this.currentSender === senderId) {
      return;
    }

    const url = `${this.baseUrl}?roomId=${encodeURIComponent(normalizedRoom)}&senderId=${encodeURIComponent(senderId)}`;
    this.status$.next('CONNECTING');

    try {
      this.socket = new WebSocket(url);

      this.socket.onopen = () => {
        this.reconnectAttempts = 0;
        this.ngZone.run(() => this.status$.next('OPEN'));
        
        // ✅ CORREGIDO: Mensaje de autenticación con estructura SyncMsg estándar
        const authMsg: SyncMsg = {
          type: 'auth',
          roomId: this.currentRoom,
          senderId: this.currentSender,
          timestamp: Date.now(),
          correlationId: WebsocketService.genCorr(),
          data: { isHost: false } // Default to false, orchestrator can update later or we can pass it in connect
        };
        
        try { 
          this.socket!.send(JSON.stringify(authMsg)); 
        } catch (e) { 
          console.warn('auth send failed', e); 
        }
        
        // Vaciar cola de envío
        while (this.sendQueue.length && this.socket && this.socket.readyState === WebSocket.OPEN) {
          const obj = this.sendQueue.shift();
          try { 
            this.socket.send(JSON.stringify(obj)); 
          } catch (e) { 
            console.warn('send flush failed', e); 
          }
        }
        
        // Iniciar heartbeat
        this.startHeartbeat();
      };

    this.socket.onmessage = (event) => {
  this.ngZone.run(() => {
    try {
      const parsed: SyncMsg = JSON.parse(event.data);
      
      // Detectar ACK por type='ack' y correlationId
      if (parsed.type === 'ack' && parsed.correlationId) {
        const entry = this.ackMap.get(parsed.correlationId);
        if (entry) {
          clearTimeout(entry.timeoutId);
          this.ackMap.delete(parsed.correlationId);
          // Resolver con el ACK parseado
          if (parsed.success) {
            entry.resolve(parsed);
          } else {
            entry.reject(new Error(parsed.reason || 'ACK fallido'));
          }
          return; // Mensaje consumido
        }
      }
      
      // Emitir otros mensajes como eventos normales
      this.messages$.next(parsed);
    } catch (err) {
      console.warn('Error parsing WebSocket message:', err);
      // ✅ CORREGIDO: Mensaje de error con propiedades opcionales
      const errorMsg: SyncMsg = {
        type: 'system',
        subType: 'parse_error',
        timestamp: Date.now(),
        data: { raw: event.data, error: 'Parse error' },
        roomId: '',
        senderId: '',
        correlationId: '',
        success: false,
        reason: ''
      };
      this.messages$.next(errorMsg);
    }
  });
};

      this.socket.onclose = (event) => {
        this.ngZone.run(() => {
          console.log(`WebSocket closed: ${event.code} - ${event.reason}`);
          this.status$.next('CLOSED');
        });
        this.scheduleReconnect();
      };

      this.socket.onerror = (error) => {
        this.ngZone.run(() => {
          console.error('WebSocket error:', error);
          this.status$.next('ERROR');
        });
      };
    } catch (e) {
      console.error('Error creating WebSocket:', e);
      this.scheduleReconnect();
    }
  }

  /**
   * Genera correlationId único (UUID v4 preferido)
   */
  private static genCorr(): string {
    try {
      if (typeof crypto !== 'undefined' && crypto.randomUUID) {
        return crypto.randomUUID();
      }
    } catch {}
    // Fallback para navegadores antiguos
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
      const r = Math.random() * 16 | 0;
      const v = c == 'x' ? r : (r & 0x3 | 0x8);
      return v.toString(16);
    });
  }

  private startHeartbeat(): void {
  this.stopHeartbeat();
  this.heartbeatTimerId = window.setInterval(() => {
    try {
      if (this.socket && this.socket.readyState === WebSocket.OPEN) {
        const ping: SyncMsg = {
          type: 'heartbeat',
          roomId: this.currentRoom,
          senderId: this.currentSender,
          timestamp: Date.now(),
          correlationId: WebsocketService.genCorr()
        };
        this.socket.send(JSON.stringify(ping));
      }
    } catch (e) { 
      console.warn('heartbeat send failed', e); 
    }
  }, 30000);
}

  private stopHeartbeat(): void {
    if (this.heartbeatTimerId) { 
      clearInterval(this.heartbeatTimerId); 
      this.heartbeatTimerId = undefined; 
    }
  }

  /**
   * Programa reconexión automática con backoff exponencial
   */
  private scheduleReconnect(): void {
    const maxAttempts = 6;
    if (this.reconnectAttempts >= maxAttempts) {
      console.warn('Max reconnect attempts reached');
      return;
    }
    
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
    this.reconnectAttempts++;
    
    if (this.reconnectTimerSub) { 
      clearTimeout(this.reconnectTimerSub); 
    }
    
    this.ngZone.runOutsideAngular(() => {
      this.reconnectTimerSub = setTimeout(() => {
        this.ngZone.run(() => {
          if (this.currentRoom && this.currentSender) {
            console.log(`Attempting reconnect ${this.reconnectAttempts}/${maxAttempts}`);
            this.connect(this.currentRoom, this.currentSender);
          }
        });
      }, delay);
    });
  }

  /**
 * Envía payload y espera ACK del backend
 */
  async sendWithAck(payload: SyncMsg, timeoutMs = this.ackTimeoutMs): Promise<SyncMsg> {
    if (!payload) {
      throw new Error('Payload must be a SyncMsg object');
    }

    // Usar correlationId estándar
    const corr = WebsocketService.genCorr();
    payload.correlationId = corr;
    
    // Asegurar timestamp si no existe
    if (!payload.timestamp) {
      payload.timestamp = Date.now();
    }

    return new Promise<SyncMsg>((resolve, reject) => {
      const timeoutId = window.setTimeout(() => {
        this.ackMap.delete(corr);
        reject(new Error(`ACK timeout after ${timeoutMs}ms`));
      }, timeoutMs);
      
      this.ackMap.set(corr, { 
        resolve: (ackMsg: SyncMsg) => resolve(ackMsg), 
        reject, 
        timeoutId 
      });
      
      try {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
          this.socket.send(JSON.stringify(payload));
        } else {
          // Encolar si no hay conexión
          if (this.sendQueue.length >= this.maxSendQueue) {
            const dropped = this.sendQueue.shift();
            console.warn('Send queue full, dropped message:', dropped);
          }
          this.sendQueue.push(payload);
        }
      } catch (e) {
        clearTimeout(timeoutId);
        this.ackMap.delete(corr);
        reject(e);
      }
    });
  }
    /**
   * Envía objeto sin esperar ACK (fire-and-forget)
   */
  sendObject(payload: SyncMsg): void {
    if (!payload) return;
    
    try {
      // Asegurar estructura básica
      if (!payload.timestamp) {
        payload.timestamp = Date.now();
      }

      if (this.socket && this.socket.readyState === WebSocket.OPEN) {
        this.socket.send(JSON.stringify(payload));
      } else {
        // Encolar si no hay conexión
        if (this.sendQueue.length >= this.maxSendQueue) {
          this.sendQueue.shift();
        }
        this.sendQueue.push(payload);
      }
    } catch (e) {
      console.warn('WebsocketService.sendObject failed:', e);
    }
  }
  /**
   * Desconecta del WebSocket y limpia recursos
   */
  disconnect(): void {
    this.reconnectAttempts = Infinity; // Prevenir reconexiones automáticas
    
    if (this.reconnectTimerSub) { 
      clearTimeout(this.reconnectTimerSub); 
      this.reconnectTimerSub = undefined; 
    }
    
    this.stopHeartbeat();
    
    try { 
      this.socket?.close(1000, 'Client disconnect'); 
    } catch (e) {
      console.warn('Error closing WebSocket:', e);
    }
    
    this.socket = undefined;
    this.sendQueue = [];
    
    // Rechazar todas las promesas ACK pendientes
    for (const [corr, entry] of this.ackMap.entries()) {
      clearTimeout(entry.timeoutId);
      entry.reject(new Error('WebSocket disconnected'));
    }
    this.ackMap.clear();
    
    this.currentRoom = undefined;
    this.currentSender = undefined;
    
    this.ngZone.run(() => {
      this.status$.next('CLOSED');
    });
  }

  /**
   * Observable de mensajes entrantes
   */
  get messages(): Observable<SyncMsg> { 
    return this.messages$.asObservable(); 
  }

  /**
   * Observable de estado de conexión
   */
  get status(): Observable<'CONNECTING' | 'OPEN' | 'CLOSED' | 'ERROR'> { 
    return this.status$.asObservable(); 
  }
  /**
   * Normaliza ID de sala (elimina caracteres especiales)
   */
  private static normalizeRoomId(raw?: string | null): string | null {
    if (!raw) return null;
    return raw.replace(/^[{<"]|[}>"]$/g, '').trim();
  }

  /**
   * Obtiene estado actual del WebSocket
   */
  get readyState(): number | undefined {
    return this.socket?.readyState;
  }

  /**
   * Verifica si está conectado
   */
  isConnected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }
}