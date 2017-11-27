/* Copyright (C) 2017 [Gobierno de Espana]
 * This file is part of FIRe.
 * FIRe is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * Date: 08/09/2017
 * You may contact the copyright holder at: soporte.afirma@correo.gob.es
 */
package es.gob.fire.server.services.internal.sessions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpSession;

import es.gob.fire.server.services.internal.FireSession;
import es.gob.fire.signature.ConfigManager;

/**
 * Gestor para el guardado de sesiones que utiliza un directorio en disco como
 * lugar para el guardado y lectura de datos de sesi&oacute;n. Este mecanismo
 * puede conllevar un uso intensivo de la unidad de disco y problemas de
 * inconsistencia si un retardo de escritura en disco permitiese que se buscase
 * una sesi&oacute;n antes de que terminase de grabarse.
 */
public class FileSystemSessionsDAO implements SessionsDAO {

	private static final Logger LOGGER = Logger.getLogger(FileSystemSessionsDAO.class.getName());

	private static final String SESSIONS_TEMP_DIR = "sessions"; //$NON-NLS-1$

	/** N&uacute;mero de usos maximos antes de realizar una limpieza de sesiones abandonadas en disco. */
	private static final int MAX_USES = 2000;

	/** Tiempo maximo que puede estar en disco una sesi&oacute;n. */
	private static final long MAX_SESSION_PERIOD = 2*60*60*1000; // 2 Horas

	/** Directorio de sesiones. */
	private final File dir;

	/** Numero de recuperaciones de sesion realizados. */
	private int uses = 0;

	/**
	 * Construye el gestor y crea el directorio para el guardado.
	 */
	public FileSystemSessionsDAO() {

		this.dir = new File(ConfigManager.getTempDir(), SESSIONS_TEMP_DIR);

		if (!this.dir.exists()) {
			this.dir.mkdirs();
		}
		else {
			// Al iniciar el DAO limpiamos las sesiones caducadas que existiesen previamente
			new CleanerThread(this.dir).start();
		}
	}

	@Override
	public boolean existsSession(final String id) {
		return Files.exists(new File(this.dir, id).toPath());
	}

	@Override
	public FireSession recoverSession(final String id, final HttpSession session) {

		// Si hemos alcanzado el numero maximo de accesos, iniciamos
		// la limpieza del directorio temporal y reiniciamos el contador
		if (++this.uses > MAX_USES) {
			new CleanerThread(this.dir).start();
			this.uses = 0;
		}

		final File sessionFile = new File(this.dir, id);

		final Map<String, Object> sessionData;
		try (final FileInputStream fis = new FileInputStream(sessionFile);) {
			try (ObjectInputStream ois = new ObjectInputStream(fis)) {
				sessionData = (Map<String, Object>) ois.readObject();
			}
		}
		catch (final FileNotFoundException e) {
			LOGGER.warning("No se encontro en disco la sesion: " + id); //$NON-NLS-1$
			return null;
		}
		catch (final Exception e) {
			LOGGER.log(Level.SEVERE, "Error al cargar de disco la sesion: " + id, e); //$NON-NLS-1$
			return null;
		}

		// Ya que la fecha del fichero es la fecha en la que se guardo y este tambien sera el momento
		// en el que se actualizo la fecha de expiracion, podemos recoger esa fecha y sumarle el tiempo
		// de vigencia para obtener la misma fecha de caducidad
		return FireSession.newSession(id, sessionData, session,
				sessionFile.lastModified() + ConfigManager.getTempsTimeout());
	}

	@Override
	public void saveSession(final FireSession session) {
		try (final FileOutputStream fos = new FileOutputStream(new File(this.dir, session.getTransactionId()));) {
			try (ObjectOutputStream oos = new ObjectOutputStream(fos)) {
				oos.writeObject(session.getAttributtes());
			}
		}
		catch (final Exception e) {
			LOGGER.log(Level.SEVERE, "Error al guardar en disco la sesion: " + session.getTransactionId(), e); //$NON-NLS-1$
		}
	}

	@Override
	public void removeSession(final String id) {
		try {
			Files.delete(new File(this.dir, id).toPath());
		} catch (final IOException e) {
			LOGGER.warning("No se pudo eliminar la sesion " + id); //$NON-NLS-1$
		}
	}

	/**
	 * Hilo para la limpieza de sesiones en disco.
	 */
	class CleanerThread extends Thread {

		private final Logger LOGGER_THREAD = Logger.getLogger(CleanerThread.class.getName());

		private final File cleaningDir;

		public CleanerThread(final File dir) {
			this.cleaningDir = dir;
		}

		@Override
		public void run() {

			File[] files;
			try {
				files = this.cleaningDir.listFiles();
			}
			catch (final Exception e) {
				this.LOGGER_THREAD.log(Level.WARNING, "No se pudo realizar la limpieza del directorio de sesiones", e); //$NON-NLS-1$
				return;
			}

			final long currentTime = System.currentTimeMillis();
			for (final File file : files) {
				if (currentTime > file.lastModified() + MAX_SESSION_PERIOD) {
					try {
						Files.deleteIfExists(file.toPath());
					} catch (final IOException e) {
						// No hacemos nada
					}
				}
			}
		}
	}
}
