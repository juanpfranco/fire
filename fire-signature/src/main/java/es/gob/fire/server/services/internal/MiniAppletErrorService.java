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
import java.util.Properties;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servicio para procesar los errores encontrados por el MiniApplet y los clientes nativos.
 */
public class MiniAppletErrorService extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger LOGGER = Logger.getLogger(MiniAppletErrorService.class.getName());

	@Override
	protected void service(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {

		final String transactionId = request.getParameter(ServiceParams.HTTP_PARAM_TRANSACTION_ID);
		// Comprobamos que se hayan prorcionado los parametros indispensables
        if (transactionId == null || transactionId.isEmpty()) {
        	LOGGER.warning("No se ha proporcionado el ID de transaccion"); //$NON-NLS-1$
        	response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        final String userId = request.getParameter(ServiceParams.HTTP_PARAM_SUBJECT_ID);

        // Se recibe el tipo de error pero por ahora no hacemos nada con el
		//final String errorType = request.getParameter(ServiceParams.HTTP_PARAM_ERROR_TYPE);
		final String errorMessage = request.getParameter(ServiceParams.HTTP_PARAM_ERROR_MESSAGE);
		String errorUrl = request.getParameter(ServiceParams.HTTP_PARAM_ERROR_URL);

		final FireSession session = SessionCollector.getFireSession(transactionId, userId, request.getSession(false), true, false);
		if (session == null) {
        	LOGGER.warning("La sesion no existe"); //$NON-NLS-1$
        	SessionCollector.removeSession(transactionId);
        	response.sendRedirect(errorUrl);
    		return;
        }

        // Obtenenmos la configuracion del conector
        final Properties connConfig		= (Properties) session.getObject(ServiceParams.SESSION_PARAM_CONNECTION_CONFIG);
    	if (connConfig == null || !connConfig.containsKey(ServiceParams.CONNECTION_PARAM_ERROR_URL)) {
            setErrorToSession(session, OperationError.INVALID_STATE, null);
            response.sendRedirect(errorUrl);
        	return;
    	}
    	errorUrl = connConfig.getProperty(ServiceParams.CONNECTION_PARAM_ERROR_URL);

		// Asignamos los mensajes de error
    	setErrorToSession(session, OperationError.SIGN_MINIAPPLET, errorMessage);
        response.sendRedirect(errorUrl);
	}

	// Agrega a la informacion de sesion el error producido durante la operacion
	private static void setErrorToSession(final FireSession session, final OperationError error, final String errorMessage) {
		SessionCollector.cleanSession(session);
		session.setAttribute(ServiceParams.SESSION_PARAM_ERROR_TYPE, Integer.toString(error.getCode()));
		session.setAttribute(ServiceParams.SESSION_PARAM_ERROR_MESSAGE, errorMessage != null ?
				errorMessage : error.getMessage());
		SessionCollector.commit(session);
	}
}
