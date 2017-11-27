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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import es.gob.afirma.core.misc.Base64;
import es.gob.afirma.core.signers.TriphaseData;
import es.gob.fire.server.services.FIReServiceOperation;
import es.gob.fire.server.services.FIReTriHelper;
import es.gob.fire.server.services.ServiceUtil;
import es.gob.fire.signature.AplicationsDAO;
import es.gob.fire.signature.ConfigManager;
import es.gob.fire.signature.DocInfo;
import es.gob.fire.signature.LoadResult;
import es.gob.fire.signature.connector.FIReConnector;
import es.gob.fire.signature.connector.FIReConnectorFactory;
import es.gob.fire.signature.connector.FIReConnectorFactoryException;
import es.gob.fire.signature.connector.FIReConnectorNetworkException;
import es.gob.fire.signature.connector.FIReConnectorUnknownUserException;

/** Servicio de carga de datos para su posterior firma en servidor.
 * @author Tom&aacute;s Garc&iacute;a-Mer&aacute;s. */
public final class PreSignService extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(PreSignService.class.getName());

    private static final String URL_ENCODING = "utf-8"; //$NON-NLS-1$

    /** Carga los datos para su posterior firma en servidor.
     * @see HttpServlet#service(HttpServletRequest request, HttpServletResponse response) */
    @Override
    protected void service(final HttpServletRequest request,
    		               final HttpServletResponse response) throws ServletException, IOException {

    	final String transactionId  = request.getParameter(ServiceParams.HTTP_PARAM_TRANSACTION_ID);
    	final String userId  		= request.getParameter(ServiceParams.HTTP_PARAM_SUBJECT_ID);
    	final String certB64        = request.getParameter(ServiceParams.HTTP_PARAM_CERT);
    	final String remoteSubjId   = request.getParameter(ServiceParams.HTTP_PARAM_SUBJECT_ID);
    	String redirectErrorUrl 	= request.getParameter(ServiceParams.HTTP_PARAM_ERROR_URL);

        // Comprobamos que se hayan prorcionado los parametros indispensables
        if (transactionId == null || transactionId.isEmpty()) {
        	LOGGER.warning("No se ha proporcionado el ID de transaccion"); //$NON-NLS-1$
        	response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        if (certB64 == null || certB64.isEmpty()) {
            LOGGER.warning("No se ha proporcionado el certificado del firmante"); //$NON-NLS-1$
            SessionCollector.removeSession(transactionId);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        if (remoteSubjId == null || remoteSubjId.isEmpty()) {
            LOGGER.warning("No se ha proporcionado el identificador del firmante"); //$NON-NLS-1$
            SessionCollector.removeSession(transactionId);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        if (redirectErrorUrl == null || redirectErrorUrl.isEmpty()) {
            LOGGER.warning("No se ha proporcionado la URL de redireccion de error"); //$NON-NLS-1$
            SessionCollector.removeSession(transactionId);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        redirectErrorUrl = URLDecoder.decode(redirectErrorUrl, URL_ENCODING);

        FireSession session = SessionCollector.getFireSession(transactionId, userId, request.getSession(false), false, false);
        if (session == null) {
        	LOGGER.warning("La sesion no contiene los datos de la operacion"); //$NON-NLS-1$
        	SessionCollector.removeSession(transactionId);
        	response.sendRedirect(redirectErrorUrl);
        	return;
		}

		// Si la operacion anterior no fue de solicitud de firma, forzamos a que se recargue por si faltan datos
		if (SessionFlags.OP_SIGN != session.getObject(ServiceParams.SESSION_PARAM_PREVIOUS_OPERATION)) {
			session = SessionCollector.getFireSession(transactionId, userId, request.getSession(false), false, true);
		}

    	// Leemos los valores necesarios de la configuracion
        final String op          	= session.getString(ServiceParams.SESSION_PARAM_OPERATION);
    	final String appId          = session.getString(ServiceParams.SESSION_PARAM_APPLICATION_ID);
        final String subjectId      = session.getString(ServiceParams.SESSION_PARAM_SUBJECT_ID);
        final String algorithm      = session.getString(ServiceParams.SESSION_PARAM_ALGORITHM);
        final String extraParamsB64 = session.getString(ServiceParams.SESSION_PARAM_EXTRA_PARAM);
        final String subOperation   = session.getString(ServiceParams.SESSION_PARAM_CRYPTO_OPERATION);
        final String format         = session.getString(ServiceParams.SESSION_PARAM_FORMAT);
        final Properties connConfig = (Properties) session.getObject(ServiceParams.SESSION_PARAM_CONNECTION_CONFIG);
        final boolean stopOnError   = Boolean.parseBoolean(session.getString(ServiceParams.SESSION_PARAM_BATCH_STOP_ON_ERROR));

        // Comprobaciones de seguridad
    	if (appId == null || appId.isEmpty()) {
            LOGGER.warning("No se ha proporcionado el identificador de la aplicacion"); //$NON-NLS-1$
            SessionCollector.removeSession(session);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

    	if (ConfigManager.isCheckApplicationNeeded()) {
        	LOGGER.info("Se realizara la validacion de aplicacion en la base de datos"); //$NON-NLS-1$
	        try {
	        	if (!AplicationsDAO.checkApplicationId(appId)) {
	        		LOGGER.warning("Se proporciono un identificador de aplicacion no valido. Se rechaza la peticion"); //$NON-NLS-1$
	        		SessionCollector.removeSession(session);
	        		response.sendError(HttpServletResponse.SC_FORBIDDEN);
	        		return;
	        	}
	        }
	        catch (final Exception e) {
	        	LOGGER.severe("Ocurrio un error grave al validar el identificador de la aplicaci&oacute;n :" +e); //$NON-NLS-1$

	        	setErrorToSession(session, OperationError.INTERNAL_ERROR);
	        	response.sendRedirect(redirectErrorUrl);
	        	return;
	        }
        }
        else {
        	LOGGER.warning("No se realiza la validacion de aplicacion en la base de datos"); //$NON-NLS-1$
        }

    	// Comprobaciones
        if (subjectId == null || subjectId.isEmpty()) {
            LOGGER.warning(
        		"No se encontro en la sesion el identificador del titular de la clave de firma" //$NON-NLS-1$
            );
            setErrorToSession(session, OperationError.INVALID_STATE);
        	response.sendRedirect(redirectErrorUrl);
            return;
        }

        // El identificador de usuario proporcionado debe ser el que estaba habilitado en la sesion
        if (!remoteSubjId.equals(subjectId)) {
        	LOGGER.warning("El identificador de usuario proporcionado no coincide con el de la sesion"); //$NON-NLS-1$
            SessionCollector.removeSession(session);
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        if (algorithm == null || algorithm.isEmpty()) {
            LOGGER.warning("No se encontro en la sesion el algoritmo de firma"); //$NON-NLS-1$
            setErrorToSession(session, OperationError.INVALID_STATE);
        	response.sendRedirect(redirectErrorUrl);
            return;
        }

        if (subOperation == null || subOperation.isEmpty()) {
            LOGGER.warning("No se encontro en la sesion la operacion de firma a realizar"); //$NON-NLS-1$
            setErrorToSession(session, OperationError.INVALID_STATE);
        	response.sendRedirect(redirectErrorUrl);
            return;
        }

        if (format == null || format.isEmpty()) {
            LOGGER.warning("No se encontro en la sesion el formato de firma"); //$NON-NLS-1$
            setErrorToSession(session, OperationError.INVALID_STATE);
        	response.sendRedirect(redirectErrorUrl);
            return;
        }

		if (connConfig == null || !connConfig.containsKey(ServiceParams.CONNECTION_PARAM_ERROR_URL)) {
			LOGGER.warning("No se encontro en la sesion la URL redireccion de error para la operacion"); //$NON-NLS-1$
            setErrorToSession(session, OperationError.INVALID_STATE);
        	response.sendRedirect(redirectErrorUrl);
			return;
		}
		redirectErrorUrl = connConfig.getProperty(ServiceParams.CONNECTION_PARAM_ERROR_URL);

        Properties extraParams;
        try {
        	extraParams = ServiceUtil.base642Properties(extraParamsB64);
        }
        catch (final Exception e) {
        	LOGGER.warning("Se ha proporcionado extraParams mal formados"); //$NON-NLS-1$
        	setErrorToSession(session, OperationError.INVALID_STATE);
        	response.sendRedirect(redirectErrorUrl);
            return;
		}

        // Decodificamos el certificado de firma
        final X509Certificate signerCert;
        try {
            signerCert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate( //$NON-NLS-1$
                new ByteArrayInputStream(Base64.decode(certB64, true))
            );
        }
        catch (final Exception e) {
        	LOGGER.warning("No se ha podido decodificar el certificado del firmante: " + e); //$NON-NLS-1$
        	setErrorToSession(session, OperationError.INVALID_STATE);
        	response.sendRedirect(redirectErrorUrl);
        	return;
        }

        // Calculamos la prefirma de una forma u otra segun se trate de una operacion de
        // firma individual o la de un lote.
        TriphaseData td;
        if (FIReServiceOperation.SIGN.getId().equals(op)) {
        	final byte[] data;
        	try {
        		data = TempFilesHelper.retrieveTempData(transactionId);
        	}
        	catch (final Exception e) {
        		LOGGER.warning("No se han podido recuperar los datos de la operacion: " + e); //$NON-NLS-1$
            	setErrorToSession(session, OperationError.INVALID_STATE);
            	response.sendRedirect(redirectErrorUrl);
        		return;
        	}

            // En caso de haberse indicado un nombre de fichero a traves del parametro
            // de configuracion, los pasamos a los extraParams para que se puedan procesar
            // en el conector como parte de la configuracion de la firma
            final DocInfo docInfo = DocInfo.extractDocInfo(connConfig);
            DocInfo.addDocInfoToSign(extraParams, docInfo);

        	try {
                td = FIReTriHelper.getPreSign(
                    subOperation,
                    format,
                    algorithm,
                    extraParams,
        			signerCert,
                    data
        		);
            }
            catch (final Exception e) {
                LOGGER.log(Level.SEVERE, "No se ha podido obtener la prefirma", e); //$NON-NLS-1$
                setErrorToSession(session, OperationError.SIGN_SERVICE_PRESIGN);
                response.sendRedirect(redirectErrorUrl);
                return;
            }

        }
        else if (FIReServiceOperation.CREATE_BATCH.getId().equals(op)) {

        	final BatchResult batchResult = (BatchResult) session.getObject(ServiceParams.SESSION_PARAM_BATCH_RESULT);
        	if (batchResult == null || batchResult.documentsCount() == 0) {
        		LOGGER.log(Level.WARNING, "No se han agregado documentos al lote"); //$NON-NLS-1$
            	setErrorToSession(session, OperationError.RECOVER_BATCH_NO_DOCUMENTS);
            	response.sendRedirect(redirectErrorUrl);
        		return;
			}

            final List<BatchDocument> documents = new ArrayList<>();
        	final Iterator<String> it = batchResult.iterator();
        	while (it.hasNext()) {

        		final String docId = it.next();
        		if (batchResult.isSignFailed(docId)) {
        			LOGGER.warning("La firma estaba marcada como erronea desde el inicio"); //$NON-NLS-1$
            		if (stopOnError) {
						setErrorToSession(session, OperationError.SIGN_SERVICE_PRESIGN);
						response.sendRedirect(redirectErrorUrl);
	            		return;
					}
        			continue;
        		}

        		byte[] data;
        		try {
        			data = TempFilesHelper.retrieveTempData(batchResult.getDocumentReference(docId));
            	}
            	catch (final Exception e) {
            		LOGGER.warning("No se pudo recuperar uno de los datos agregados al lote: " + e); //$NON-NLS-1$
            		if (stopOnError) {
						setErrorToSession(session, OperationError.SIGN_SERVICE_PRESIGN);
						response.sendRedirect(redirectErrorUrl);
	            		return;
					}
            		data = null;
            	}
        		documents.add(new BatchDocument(docId, data, batchResult.getSignConfig(docId), batchResult.getDocInfo(docId)));
        	}

            if (documents.size() == 0) {
            	LOGGER.warning("No se han podido recuperar los datos a firmar"); //$NON-NLS-1$
            	SessionCollector.removeSession(session);
                response.sendError(
            		HttpServletResponse.SC_BAD_REQUEST,
                    "No se han podido recuperar los datos a firmar" //$NON-NLS-1$
        		);
                return;
            }

            try {
                td = FIReTriHelper.getPreSign(
                    subOperation,
                    format,
                    algorithm,
                    extraParams,
        			signerCert,
                    documents,
                    stopOnError
        		);
            }
            catch (final Exception e) {
                LOGGER.log(Level.SEVERE, "No se ha podido obtener la prefirma", e); //$NON-NLS-1$
                setErrorToSession(session, OperationError.SIGN_SERVICE_PRESIGN);
                response.sendRedirect(redirectErrorUrl);
                return;
            }

            // Actualizamos el resultado de las firmas en caso de haber detectado algun error
            // al procesar su documento asociado
            for (final BatchDocument doc : documents) {
        		if (doc.getResult() != null) {
        			batchResult.setErrorResult(doc.getId(), doc.getResult());
        		}
        	}
        }
        else {
        	LOGGER.warning("Operacion no soportada: " + op); //$NON-NLS-1$
        	SessionCollector.removeSession(session);
    		response.sendError(
    				HttpServletResponse.SC_BAD_REQUEST,
    				"Operacion no soportada" //$NON-NLS-1$
    				);
    		return;
        }

        // Obtenemos el conector con el backend ya configurado
        final FIReConnector connector;
        try {
            connector = FIReConnectorFactory.getClaveFirmaConnector(connConfig);
        }
        catch (final FIReConnectorFactoryException e) {
            LOGGER.log(Level.SEVERE, "Error en la configuracion del conector con el servicio de custodia", e); //$NON-NLS-1$
            setErrorToSession(session, OperationError.INTERNAL_ERROR);
            response.sendRedirect(redirectErrorUrl);
            return;
        }

        final LoadResult lr;
        try {
            lr = connector.loadDataToSign(
                subjectId,
                algorithm,
                td,
                CertificateFactory.getInstance("X.509").generateCertificate( //$NON-NLS-1$
                    new ByteArrayInputStream(Base64.decode(ServiceUtil.undoUrlSafe(certB64)))
                )
            );
        }
        catch (final FIReConnectorUnknownUserException e) {
        	LOGGER.severe("El usuario " + subjectId + " no tiene certificados en el sistema: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        	setErrorToSession(session, OperationError.UNKNOWN_USER);
        	response.sendRedirect(redirectErrorUrl);
        	return;
        }
        catch (final FIReConnectorNetworkException e) {
        	LOGGER.log(Level.SEVERE, "No se ha podido conectar con el sistema: " + e, e); //$NON-NLS-1$
        	setErrorToSession(session, OperationError.SIGN_SERVICE_NETWORK);
        	response.sendRedirect(redirectErrorUrl);
            return;
        }
        catch (final Exception e) {
            LOGGER.log(Level.SEVERE, "Error en la carga de datos: " + e, e); //$NON-NLS-1$
            setErrorToSession(session, OperationError.SIGN_SERVICE);
            response.sendRedirect(redirectErrorUrl);
            return;
        }

        // Guardamos en la sesion:
        // - El resultado de la prefirma de los datos.
        // - El ID de la transaccion contra el servicio remoto, necesario para solicitar completar la firma
        // - El certificado utilizado para la firma.
        // - Un valor indicativo de que se ha redirigido a la pasarela de autorizacion
        session.setAttribute(ServiceParams.SESSION_PARAM_TRIPHASE_DATA, Base64.encode(lr.getTriphaseData().toString().getBytes(StandardCharsets.UTF_8), true));
        session.setAttribute(ServiceParams.SESSION_PARAM_REMOTE_TRANSACTION_ID, lr.getTransactionId());
        session.setAttribute(ServiceParams.SESSION_PARAM_CERT, certB64);
        session.setAttribute(ServiceParams.SESSION_PARAM_PREVIOUS_OPERATION, SessionFlags.OP_PRE);
        session.setAttribute(ServiceParams.SESSION_PARAM_REDIRECTED, Boolean.TRUE);

        SessionCollector.commit(session);

        // Redirigimos al usuario a la pantalla de autorizacion indicada por el conector
        response.sendRedirect(lr.getRedirectUrl());
    }

	private static void setErrorToSession(final FireSession session, final OperationError error) {
		SessionCollector.cleanSession(session);
		session.setAttribute(ServiceParams.SESSION_PARAM_ERROR_TYPE, Integer.toString(error.getCode()));
		session.setAttribute(ServiceParams.SESSION_PARAM_ERROR_MESSAGE, error.getMessage());
		SessionCollector.commit(session);
	}
}
