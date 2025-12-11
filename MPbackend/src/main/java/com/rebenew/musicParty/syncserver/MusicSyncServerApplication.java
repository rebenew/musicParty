package com.rebenew.musicParty.syncserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MusicSyncServerApplication {
	public static void main(String[] args) {
		SpringApplication.run(MusicSyncServerApplication.class, args);
		System.out.println("Servidor WebSocket iniciado en http://localhost:8080");
	}
}