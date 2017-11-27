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
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import es.gob.fire.server.services.HttpCustomErrors;
import es.gob.fire.signature.GenerateCertificateResult;
import es.gob.fire.signature.connector.FIReCertificateAvailableException;
import es.gob.fire.signature.connector.FIReCertificateException;
import es.gob.fire.signature.connector.FIReConnectorFactoryException;
import es.gob.fire.signature.connector.FIReConnectorNetworkException;
import es.gob.fire.signature.connector.FIReConnectorUnknownUserException;
import es.gob.fire.signature.connector.WeakRegistryException;

/**
 * Servlet implementation class RequestNewCertificateService
 */
public final class RequestNewCertificateService extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger LOGGER = Logger.getLogger(RequestNewCertificateService.class.getName());

	/**
	 * @see HttpServlet#service(HttpServletRequest request, HttpServletResponse response)
	 */
	@Override
	protected void service(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {

		final String transactionId  = request.getParameter(ServiceParams.HTTP_PARAM_TRANSACTION_ID);
		final String subjectId  = request.getParameter(ServiceParams.HTTP_PARAM_SUBJECT_ID);

		FireSession session = SessionCollector.getFireSession(transactionId, subjectId, request.getSession(false), true, false);
        if (session == null) {
    		LOGGER.warning("La transaccion no se ha inicializado o ha caducado"); //$NON-NLS-1$
    		response.sendError(HttpCustomErrors.INVALID_TRANSACTION.getErrorCode());
    		return;
        }

        // Si la operacion anterior no fue la solicitud de una firma, forzamos a que se recargue por si faltan datos
        if (SessionFlags.OP_SIGN != session.getObject(ServiceParams.SESSION_PARAM_PREVIOUS_OPERATION)) {
        	session = SessionCollector.getFireSession(transactionId, subjectId, request.getSession(false), false, true);
        }

		final Properties connConfig	= (Properties) session.getObject(ServiceParams.SESSION_PARAM_CONNECTION_CONFIG);

    	final Properties generationConfig = connConfig != null ? (Properties) connConfig.clone() : new Properties();
    	final String errorUrlRedirection = generationConfig.getProperty(ServiceParams.CONNECTION_PARAM_ERROR_URL);

    	// Creamos una configuracion igual a la de firma para la generacion de certificado
    	// y establecemos que la URL de redireccion en caso de exito sea la de recuperacion
    	// del certificado generado
        String localUrlBase = request.getRequestURL().toString();
        localUrlBase = localUrlBase.substring(0, localUrlBase.toString().lastIndexOf('/') + 1);

        generationConfig.setProperty(
        		ServiceParams.CONNECTION_PARAM_SUCCESS_URL,
        		localUrlBase + "ChooseNewCertificate.jsp?" + //$NON-NLS-1$
        				ServiceParams.HTTP_PARAM_SUBJECT_ID + "=" + subjectId + "&" + //$NON-NLS-1$ //$NON-NLS-2$
        				ServiceParams.HTTP_PARAM_TRANSACTION_ID + "=" + transactionId); //$NON-NLS-1$

        final GenerateCertificateResult gcr;
        try {
        	gcr = GenerateCertificateManager.generateCertificate(subjectId, generationConfig);
        }
        catch (final IllegalArgumentException e) {
        	LOGGER.warning("No se ha proporcionado el identificador del usuario que solicita el certificado"); //$NON-NLS-1$
			response.sendError(HttpServletResponse.SC_BAD_REQUEST,
        			"No se ha proporcionado el identificador del usuario que solicita el certificado"); //$NON-NLS-1$
        	return;
        }
        catch (final FIReConnectorFactoryException e) {
        	LOGGER.log(Level.SEVERE, "Error en la configuracion del conector con el servicio de custodia", e); //$NON-NLS-1$
        	response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        			"Error en la configuracion del conector con el servicio de custodia: " + e //$NON-NLS-1$
        			);
        	return;
        }
        catch (final FIReConnectorNetworkException e) {
        	LOGGER.log(Level.SEVERE, "No se ha podido conectar con el sistema: " + e, e); //$NON-NLS-1$
        	response.sendError(
        			HttpServletResponse.SC_REQUEST_TIMEOUT,
        			"No se ha podido conectar con el sistema: " + e); //$NON-NLS-1$
        	return;
        }
        catch (final FIReCertificateAvailableException e) {
        	LOGGER.log(Level.SEVERE, "El usuario ya tiene un certificado del tipo indicado: " + e, e); //$NON-NLS-1$
        	response.sendError(
    				HttpCustomErrors.CERTIFICATE_AVAILABLE.getErrorCode(),
    				"El usuario ya tiene un certificado del tipo indicado: " + e); //$NON-NLS-1$
    		return;
        }
        catch (final FIReCertificateException e) {
        	LOGGER.log(Level.SEVERE, "Error en la generacion del certificado: " + e, e); //$NON-NLS-1$
        	response.sendError(
        			HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        			"Error en la generacion del certificado: " + e); //$NON-NLS-1$
        	return;
        }
        catch (final FIReConnectorUnknownUserException e) {
        	LOGGER.log(Level.SEVERE, "El usuario no esta dado de alta en el sistema: " + e, e); //$NON-NLS-1$
        	response.sendError(
        			HttpCustomErrors.NO_USER.getErrorCode(),
        			"El usuario no esta dado de alta en el sistema: " + e); //$NON-NLS-1$
        	return;
        }
        catch (final WeakRegistryException e) {
        	LOGGER.log(Level.SEVERE, "El usuario realizo un registro debil y no puede tener certificados de firma: " + e, e); //$NON-NLS-1$
        	setErrorToSession(session, OperationError.CERTIFICATES_WEAK_REGISTRY);
        	response.sendRedirect(errorUrlRedirection);
        	return;
        }
        catch (final Exception e) {
        	LOGGER.log(Level.SEVERE, "Error desconocido en la generacion del certificado: " + e, e); //$NON-NLS-1$
        	response.sendError(
        			HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        			"Error desconocido en la generacion del certificado: " + e); //$NON-NLS-1$
        	return;
        }

        final String generateTransactionId = gcr.getTransactionId();
        final String redirectUrl = gcr.getRedirectUrl();

        session.setAttribute(ServiceParams.SESSION_PARAM_GENERATE_TRANSACTION_ID, generateTransactionId);
        session.setAttribute(ServiceParams.SESSION_PARAM_REDIRECTED, Boolean.TRUE);
        session.setAttribute(ServiceParams.SESSION_PARAM_PREVIOUS_OPERATION, SessionFlags.OP_GEN);
        SessionCollector.commit(session);

        response.sendRedirect(redirectUrl);
	}

	private static void setErrorToSession(final FireSession session, final OperationError error) {
		session.setAttribute(ServiceParams.SESSION_PARAM_ERROR_TYPE, Integer.toString(error.getCode()));
		session.setAttribute(ServiceParams.SESSION_PARAM_ERROR_MESSAGE, error.getMessage());
		SessionCollector.commit(session);
	}
}
