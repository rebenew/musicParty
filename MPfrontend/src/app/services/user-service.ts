import { Injectable } from '@angular/core';
import { StorageService } from './storage-service';

@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly SENDER_ID_KEY = 'senderId';

  constructor(private storage: StorageService) {}

  getSenderId(): string {
    let senderId = this.storage.get<string>(this.SENDER_ID_KEY);
    
    if (!senderId) {
      senderId = this.generateSenderId();
      this.storage.set(this.SENDER_ID_KEY, senderId);
    }
    
    return senderId;
  }

  private generateSenderId(): string {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID();
    }
    return `s-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  }
}