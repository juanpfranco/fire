/* Copyright (C) 2017 [Gobierno de Espana]
 * This file is part of FIRe.
 * FIRe is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * Date: 08/09/2017
 * You may contact the copyright holder at: soporte.afirma@correo.gob.es
 */
package es.gob.fire.server.services.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Logger;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import es.gob.fire.server.services.RequestParameters;


/**
 * Manejador encargado de la recuperaci&oacute;n del error obtenido durante el
 * proceso de una transacci&oacute;n.
 */
public class RecoverErrorManager {

	private static final Logger LOGGER = Logger.getLogger(RecoverErrorManager.class.getName());

	/**
	 * Obtiene el error detectado durante la transacci&oacute;n.
	 * @param params Par&aacute;metros extra&iacute;dos de la petici&oacute;n.
	 * @param response Respuesta de la petici&oacute;n.
	 * @throws IOException Cuando se produce un error de lectura o env&iacute;o de datos.
	 */
	public static void recoverError(final RequestParameters params, final HttpServletResponse response)
			throws IOException {

		// Recogemos los parametros proporcionados en la peticion
		final String transactionId = params.getParameter(ServiceParams.HTTP_PARAM_TRANSACTION_ID);
		final String subjectId = params.getParameter(ServiceParams.HTTP_PARAM_SUBJECT_ID);

        // Comprobamos que se hayan proporcionado los parametros indispensables
        if (transactionId == null || transactionId.isEmpty()) {
        	LOGGER.warning("No se ha proporcionado el ID de transaccion"); //$NON-NLS-1$
        	response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        LOGGER.fine(String.format("TrId %1s: RecoverErrorManager", transactionId)); //$NON-NLS-1$

        // Recuperamos el resto de parametros de la sesion
        final FireSession session = SessionCollector.getFireSession(transactionId, subjectId, null, false, true);
        if (session == null) {
    		LOGGER.warning("La transaccion no se ha inicializado o ha caducado"); //$NON-NLS-1$
    		sendResult(response, OperationError.INVALID_SESSION);
    		return;
        }

        // Comprobamos si se declaro un error o si este es desconocido
        if (!session.containsAttribute(ServiceParams.SESSION_PARAM_ERROR_TYPE)) {

        	// Si no se declaro un error, pero sabemos que lo ultimo que se hizo es
        	// redirigir a la pasarela de autorizacion, se notifica como tal
        	if (session.containsAttribute(ServiceParams.SESSION_PARAM_REDIRECTED)) {
            	LOGGER.warning("Ocurrio un error desconocido despues de llamar a la pasarela de autorizacion de firma en la nube o a la de emision de certificados"); //$NON-NLS-1$
            	SessionCollector.removeSession(session);
            	sendResult(response, OperationError.EXTERNAL_SERVICE_ERROR);
        		return;
        	}

        	LOGGER.warning("No se ha notificado el tipo de error de la transaccion"); //$NON-NLS-1$
        	SessionCollector.removeSession(session);
        	sendResult(response, OperationError.UNDEFINED_ERROR);
        	return;
        }

        // Recuperamos la informacion de error y eliminamos la sesion
        final String errorType = session.getString(ServiceParams.SESSION_PARAM_ERROR_TYPE);
        final String errorMsg = session.getString(ServiceParams.SESSION_PARAM_ERROR_MESSAGE);

        SessionCollector.removeSession(session);

    	sendResult(response, errorType, errorMsg);
	}

	private static void sendResult(final HttpServletResponse response, final OperationError error) throws IOException {
		// El servicio devuelve el resultado de la operacion de firma.
        final OutputStream output = ((ServletResponse) response).getOutputStream();
        output.write(new TransactionResult(TransactionResult.RESULT_TYPE_ERROR, error.getCode(), error.getMessage()).encodeResult());
        output.flush();
        output.close();
	}

	private static void sendResult(final HttpServletResponse response, final String errorCode, final String errorMsg) throws IOException {
		// El servicio devuelve el resultado de la operacion de firma.
        final OutputStream output = ((ServletResponse) response).getOutputStream();
        output.write(new TransactionResult(TransactionResult.RESULT_TYPE_ERROR, Integer.parseInt(errorCode), errorMsg).encodeResult());
        output.flush();
        output.close();
	}
}
