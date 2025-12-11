// main.ts
import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { ErrorHandler, Injectable } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';


// Custom Global Error Handler
@Injectable()
class GlobalErrorHandler implements ErrorHandler {
  handleError(error: any): void {
    console.error('🔴 Error global capturado:', error);

    if (error?.ngOriginalError) {
      console.error('Error original:', error.ngOriginalError);
    }
  }
}

bootstrapApplication(AppComponent, {
  providers: [
    { provide: ErrorHandler, useClass: GlobalErrorHandler },
     provideHttpClient() 
  ],
})
.then(appRef => {
  console.log('✅ Aplicación Angular arrancada correctamente');
})
.catch(err => {
  console.error('💥 Error al bootstrap:', err);
});
