import { Injectable, NgZone } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/**
 * Servicio especializado en comunicación con extensiones Chrome
 * Maneja runtime messages, background scripts y comunicación bidireccional
 */
@Injectable({ providedIn: 'root' })
export class ExtensionBridgeService {
  private runtimeListener?: (msg: any, sender: any, sendResponse?: (r?: any) => void) => void;
  private incoming$ = new Subject<any>();
  private bgPending = new Map<string, { 
    resolve: (v: any) => void; 
    reject: (e: any) => void; 
    timeoutId: any;
    createdAt: number;
  }>();

  private readonly TIMEOUTS = {
    BACKGROUND: 5000
  };

  constructor(private ngZone: NgZone) {}

  /**
   * Verifica si el runtime de Chrome está disponible
   */
  isChromeAvailable(): boolean {
    return typeof (window as any).chrome !== 'undefined' &&
           !!(window as any).chrome.runtime && !!(window as any).chrome.runtime.sendMessage;
  }

  /**
   * Conecta con el background script de la extensión
   */
  async connect(roomId: string, senderId: string): Promise<void> {
    this.setupRuntimeListener();
    
    return new Promise<void>((resolve, reject) => {
      try {
        (window as any).chrome.runtime.sendMessage(
          { type: 'joinRoom', roomId, senderId }, 
          (resp: any) => {
            this.ngZone.run(() => {
              if (resp?.success) {
                resolve();
              } else {
                reject(new Error(resp?.reason || 'El background rechazó la unión'));
              }
            });
          }
        );
      } catch (e) {
        this.ngZone.run(() => reject(e));
      }
    });
  }

  /**
   * Envía mensaje al background y espera ACK
   */
  async sendWithAck(payload: any, timeoutMs = this.TIMEOUTS.BACKGROUND): Promise<boolean> {
    if (!this.isChromeAvailable()) {
      throw new Error('Chrome runtime no disponible');
    }

    const corr = this.genCorr();
    payload._corr = corr;

    return new Promise<boolean>((resolve) => {
      const timeoutId = setTimeout(() => {
        this.bgPending.delete(corr);
        resolve(false);
      }, timeoutMs);
      
      this.bgPending.set(corr, { 
        resolve: (v: any) => resolve(Boolean(v?.success ?? false)), 
        reject: () => resolve(false), 
        timeoutId,
        createdAt: Date.now()
      });
      
      try {
        (window as any).chrome.runtime.sendMessage({ type: 'sendSync', data: payload }, (resp: any) => {
          if (resp && resp._corr === corr) {
            const pending = this.bgPending.get(corr);
            if (pending) {
              clearTimeout(pending.timeoutId);
              this.bgPending.delete(corr);
              pending.resolve(resp);
            }
          }
        });
      } catch (e) {
        clearTimeout(timeoutId);
        this.bgPending.delete(corr);
        resolve(false);
      }
    });
  }

  /**
   * Envía mensaje sin esperar ACK (fire-and-forget)
   */
  send(payload: any): void {
    if (!this.isChromeAvailable()) return;

    try {
      (window as any).chrome.runtime.sendMessage({ type: 'sendSync', data: payload }, () => {});
    } catch (e) {
      console.warn('Error enviando mensaje al background:', e);
    }
  }

  /**
   * Controla el reproductor local de YouTube Music
   */
  async controlLocalPlayback(action: string, position?: number): Promise<void> {
    if (!this.isChromeAvailable()) return;

    try {
      await (window as any).chrome.runtime.sendMessage({
        type: 'controlPlayback',
        action,
        position
      });
    } catch (error) {
      console.warn('No se pudo controlar reproductor local:', error);
    }
  }

  /**
   * Obtiene el estado actual del reproductor
   */
  async getPlayerState(): Promise<any> {
    if (!this.isChromeAvailable()) return null;

    return new Promise((resolve) => {
      try {
        (window as any).chrome.runtime.sendMessage(
          { type: 'getPlayerState' },
          (response: any) => resolve(response?.state || null)
        );
      } catch {
        resolve(null);
      }
    });
  }

  /**
   * Abre URL en nueva pestaña (usando extensión si está disponible)
   */
  openUrl(url: string): void {
    if (!url) return;

    if (this.isChromeAvailable()) {
      try {
        (window as any).chrome.runtime.sendMessage({ type: 'openUrl', url }, () => {});
      } catch (e) {
        window.open(url, '_blank');
      }
    } else {
      window.open(url, '_blank');
    }
  }

  /**
   * Desconecta del background script
   */
  disconnect(roomId: string, senderId: string): void {
    if (this.runtimeListener) {
      try {
        (window as any).chrome.runtime.onMessage.removeListener(this.runtimeListener);
      } catch {}
      this.runtimeListener = undefined;
    }

    // Limpiar pendientes
    for (const [, pending] of this.bgPending) {
      clearTimeout(pending.timeoutId);
      pending.resolve(false);
    }
    this.bgPending.clear();

    try {
      (window as any).chrome.runtime.sendMessage({ 
        type: 'disconnect', 
        roomId, 
        senderId 
      }, () => {});
    } catch (e) {}
  }

  /**
   * Observable de mensajes entrantes del background
   */
  get onMessage(): Observable<any> {
    return this.incoming$.asObservable();
  }

  private setupRuntimeListener(): void {
    if (this.runtimeListener) return;

    this.runtimeListener = (msg: any, sender: any, sendResponse?: (r?: any) => void) => {
      this.ngZone.run(() => {
        try {
          // Normalizar mensajes del background
          const normalizedMsg = (msg.type === 'bgEvent' && (msg.data || msg.payload)) ? (msg.data || msg.payload) : msg;
          this.incoming$.next(normalizedMsg);

          // Responder inmediatamente para extensiones
          if (sendResponse) sendResponse({ received: true });
        } catch (err: any) {
          console.error('Error en runtimeListener:', err);
          if (sendResponse) sendResponse({ error: err.message });
        }
      });
    };

    try {
      (window as any).chrome.runtime.onMessage.addListener(this.runtimeListener);
    } catch (e) {
      console.warn('No se pudo agregar runtime listener:', e);
    }
  }

  private genCorr(): string {
    try {
      if (typeof crypto !== 'undefined' && crypto.randomUUID) {
        return crypto.randomUUID();
      }
    } catch {}
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 9)}`;
  }
}