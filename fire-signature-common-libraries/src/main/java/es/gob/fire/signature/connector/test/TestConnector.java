/* Copyright (C) 2017 [Gobierno de Espana]
 * This file is part of FIRe.
 * FIRe is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * Date: 08/09/2017
 * You may contact the copyright holder at: soporte.afirma@correo.gob.es
 */
package es.gob.fire.signature.connector.test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;

import es.gob.afirma.core.misc.Base64;
import es.gob.afirma.core.misc.http.HttpError;
import es.gob.afirma.core.signers.TriphaseData;
import es.gob.afirma.core.signers.TriphaseData.TriSign;
import es.gob.fire.signature.ConfigManager;
import es.gob.fire.signature.DocInfo;
import es.gob.fire.signature.GenerateCertificateResult;
import es.gob.fire.signature.LoadResult;
import es.gob.fire.signature.connector.CertificateBlockedException;
import es.gob.fire.signature.connector.FIReCertificateAvailableException;
import es.gob.fire.signature.connector.FIReCertificateException;
import es.gob.fire.signature.connector.FIReConnector;
import es.gob.fire.signature.connector.FIReConnectorNetworkException;
import es.gob.fire.signature.connector.FIReConnectorUnknownUserException;
import es.gob.fire.signature.connector.FIReSignatureException;
import es.gob.fire.signature.connector.WeakRegistryException;

/** Servicio de pruebas con certificados en disco.
 * @author Tom&aacute;s Garc&iacute;a-Mer&aacute;s. */
public class TestConnector implements FIReConnector {

	private static final Logger LOGGER = Logger.getLogger(TestConnector.class.getName());

	private static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;

	private static final String DEFAULT_TEST_URL_BASE = "https://127.0.0.1:8443/clavefirma-test-services/"; //$NON-NLS-1$

	private static final int HTTP_ERROR_NO_CERT = 522;
	private static final int HTTP_ERROR_UNKNOWN_USER = 523;
	private static final int HTTP_ERROR_BLOCKED_CERT = 524;
	private static final int HTTP_ERROR_EXISTING_CERTS = 525;
	private static final int HTTP_ERROR_WEAK_REGISTRY = 530;

    private String redirectOkUrl = null;
    private String redirectErrorUrl = null;

    private String testUrlBase = null;

	@Override
	public void init(final Properties config) {

		LOGGER.info("Inicializamos " + TestConnector.class.getName()); //$NON-NLS-1$

        // Configuramos la URL base de los servicios de prueba
        this.testUrlBase = ConfigManager.getTestServiceUrlBase();
        if (this.testUrlBase == null || this.testUrlBase.length() == 0) {
        	this.testUrlBase = DEFAULT_TEST_URL_BASE;
        }
        else if (!this.testUrlBase.endsWith("/")) { //$NON-NLS-1$
        	this.testUrlBase += "/"; //$NON-NLS-1$
        }

		if (config != null) {
        	try {
        		this.redirectOkUrl = URLEncoder.encode(config.getProperty("redirectOkUrl"), DEFAULT_ENCODING.name()); //$NON-NLS-1$
        		this.redirectErrorUrl = URLEncoder.encode(config.getProperty("redirectErrorUrl"), DEFAULT_ENCODING.name()); //$NON-NLS-1$
        	}
        	catch (final Exception e) {
        		LOGGER.severe("No se han podido configurar las URL de redireccion de exito y error: " + e); //$NON-NLS-1$
        		throw new RuntimeException("No se han podido configurar las URL de redireccion de exito y error", e); //$NON-NLS-1$
        	}
        }

		final Properties c = new Properties();
		if (ConfigManager.getTestSslKeyStore() != null) {
			c.setProperty("javax.net.ssl.keyStore", ConfigManager.getTestSslKeyStore()); //$NON-NLS-1$
			if (ConfigManager.getTestSslKeyStorePass() != null) {
				c.setProperty("javax.net.ssl.keyStorePassword", ConfigManager.getTestSslKeyStorePass()); //$NON-NLS-1$
			}
			if (ConfigManager.getTestSslKeyStoreType() != null) {
				c.setProperty("javax.net.ssl.keyStoreType", ConfigManager.getTestSslKeyStoreType()); //$NON-NLS-1$
			}
		}

		if (ConfigManager.getTestSslTrustStore() != null) {
			c.setProperty("javax.net.ssl.trustStore", ConfigManager.getTestSslTrustStore()); //$NON-NLS-1$
			if (ConfigManager.getTestSslTrustStorePass() != null) {
				c.setProperty("javax.net.ssl.trustStorePassword", ConfigManager.getTestSslTrustStorePass()); //$NON-NLS-1$
			}
			if (ConfigManager.getTestSslTrustStoreType() != null) {
				c.setProperty("javax.net.ssl.trustStoreType", ConfigManager.getTestSslTrustStoreType()); //$NON-NLS-1$
			}
		}

		try {
			ConnectionManager.configureConnection(c);
		} catch (final Exception e) {
			LOGGER.log(Level.WARNING, "Error configurando la conexion con el servicion de pruebas", e); //$NON-NLS-1$
		}
    }

	@Override
	public X509Certificate[] getCertificates(final String subjectId) throws FIReCertificateException,
	                                                                        FIReConnectorUnknownUserException,
	                                                                        FIReConnectorNetworkException,
	                                                                        CertificateBlockedException,
	                                                                        WeakRegistryException {

		final StringBuilder testUrl = new StringBuilder()
		.append(this.testUrlBase).append("TestGetCertificateService") //$NON-NLS-1$
		.append("?subjectid=").append(subjectId); //$NON-NLS-1$

		byte[] response;
		try {
			response = ConnectionManager.readUrlByGet(testUrl.toString());
		} catch (final IOException e) {

			if (e instanceof HttpError) {
				if (((HttpError) e).getResponseCode() == HTTP_ERROR_NO_CERT) {
					return new X509Certificate[0];
				}
				else if (((HttpError) e).getResponseCode() == HTTP_ERROR_UNKNOWN_USER) {
					throw new FIReConnectorUnknownUserException("El usuario no esta dado de alta en el sistema", e); //$NON-NLS-1$
				}
				else if (((HttpError) e).getResponseCode() == HTTP_ERROR_BLOCKED_CERT) {
					throw new CertificateBlockedException("El usuario tiene los certificados bloqueados", e); //$NON-NLS-1$
				}
				else if (((HttpError) e).getResponseCode() == HTTP_ERROR_WEAK_REGISTRY) {
					throw new WeakRegistryException("El realizo un registro debil y no puede tener certificados de firma", e); //$NON-NLS-1$
				}
			}

			LOGGER.log(Level.SEVERE, "Error en la llamada al servicio de prueba de recuperacion de certificados", e); //$NON-NLS-1$
			throw new FIReConnectorNetworkException("Error en la llamada al servicio de prueba de recuperacion de certificados", e); //$NON-NLS-1$
		}

		// Leemos el JSON con el listado de certificados
		final JsonReader jsonReader = Json.createReader(new ByteArrayInputStream(response));
        final JsonArray certObjects = jsonReader.readArray();
        jsonReader.close();

        final CertificateFactory certFactory;
        try {
        	certFactory = CertificateFactory.getInstance("X.509"); //$NON-NLS-1$
        }
        catch (final CertificateException e) {
        	LOGGER.severe("Error al generar la factoria de certificados: " + e); //$NON-NLS-1$
        	throw new FIReCertificateException("Error al generar la factoria de certificados", e); //$NON-NLS-1$
        }

        final X509Certificate[] certs = new X509Certificate[certObjects.size()];
        for (int i = 0; i < certObjects.size(); i++) {
        	try {
        		certs[i] = (X509Certificate) certFactory.generateCertificate(
        				new ByteArrayInputStream(
        						Base64.decode(certObjects.get(i).toString())
        						)
        				);
        	}
        	catch (final Exception e) {
        		LOGGER.log(Level.WARNING, "Error al componer un certificado del usuario", e); //$NON-NLS-1$
        		continue;
        	}
        }

		return certs;
	}

	@Override
	public LoadResult loadDataToSign(final String subjectId,
			                              final String algorithm,
			                              final TriphaseData td,
			                              final Certificate signCert) throws FIReCertificateException,
			                              									 FIReSignatureException,
			                                                                 IOException,
			                                                                 FIReConnectorUnknownUserException,
			                                                                 FIReConnectorNetworkException {
		if (td == null) {
			throw new IllegalArgumentException(
				"Los datos a cargar no pueden ser nulos" //$NON-NLS-1$
			);
		}
		if (signCert == null) {
			throw new IllegalArgumentException(
				"El certificado de firma no puede ser nulo" //$NON-NLS-1$
			);
		}

		byte[] certEncoded;
		try {
			certEncoded = signCert.getEncoded();
		} catch (final CertificateEncodingException e) {
			LOGGER.severe("Error en la codificacion del certificado: " + e); //$NON-NLS-1$
			throw new FIReCertificateException("Error en la codificacion del certificado", e); //$NON-NLS-1$
		}

		
		// Enviamos la información de los documentos separando cada campo por una coma. 
		// Si algún campo no se indica, enviamos un " " para que se incluya el hueco entre las comas.
		String infoDocumentos = "";
		for (TriSign triSign : td.getTriSigns()) {
			DocInfo docInfo = DocInfo.extractDocInfo(triSign);
			String name = docInfo.getName();
			if (name == null || name.equals("")) {
				name = " ";
			}
			String title = docInfo.getTitle();
			if (title == null || title.equals("")) {
				title = " ";
			}
			infoDocumentos += name + "," + title + ",";
		}
		
		final String urlBase = this.testUrlBase + "TestLoadDataService"; //$NON-NLS-1$
		final StringBuilder urlParameters = new StringBuilder()
		.append("subjectid=").append(subjectId) //$NON-NLS-1$
		.append("&algorithm=").append(algorithm) //$NON-NLS-1$
		.append("&certificate=").append(Base64.encode(certEncoded, true)) //$NON-NLS-1$
		.append("&triphasedata=").append(Base64.encode(td.toString().getBytes(DEFAULT_ENCODING), true)) //$NON-NLS-1$
		.append("&urlok=").append(Base64.encode(this.redirectOkUrl.getBytes(DEFAULT_ENCODING), true)) //$NON-NLS-1$
		.append("&urlerror=").append(Base64.encode(this.redirectErrorUrl.getBytes(DEFAULT_ENCODING), true)) //$NON-NLS-1$
		.append("&infoDocumentos=").append(Base64.encode(infoDocumentos.getBytes(DEFAULT_ENCODING), true)); //$NON-NLS-1$

		byte[] response;
		try {
			response = ConnectionManager.readUrlByPost(urlBase, urlParameters.toString());
		} catch (final IOException e) {
			LOGGER.log(Level.SEVERE, "Error en la llamada al servicio de prueba de carga de datos", e); //$NON-NLS-1$
			if (e instanceof HttpError) {
				if (((HttpError) e).getResponseCode() == HTTP_ERROR_UNKNOWN_USER) {
					throw new FIReConnectorUnknownUserException("El usuario no esta dado de alta en el sistema", e); //$NON-NLS-1$
				}
			}
			throw new FIReConnectorNetworkException("Error en la llamada al servicio de prueba de carga de datos", e); //$NON-NLS-1$
		}

		return new LoadResult(new String(response, StandardCharsets.UTF_8));
	}

	@Override
	public Map<String, byte[]> sign(final String transactionId) throws FIReSignatureException {

		final StringBuilder testUrl = new StringBuilder()
		.append(this.testUrlBase).append("TestSignService") //$NON-NLS-1$
		.append("?transactionid=").append(transactionId); //$NON-NLS-1$

		byte[] response;
		try {
			response = ConnectionManager.readUrlByGet(testUrl.toString());
		} catch (final IOException e) {
			LOGGER.log(Level.SEVERE, "Error en la llamada al servicio de prueba de firma", e); //$NON-NLS-1$
			throw new FIReSignatureException("Error en la llamada al servicio de prueba de firma", e); //$NON-NLS-1$
		}

		final Map<String, byte[]> result = new HashMap<>();
		final JsonReader reader = Json.createReader(new ByteArrayInputStream(response));
		try {
			final JsonArray signatures = reader.readArray();
			for (int i = 0; i < signatures.size(); i++) {
				final JsonObject signature = (JsonObject) signatures.get(i);
				result.put(signature.getString("id"), Base64.decode(signature.getString("pk1"))); //$NON-NLS-1$ //$NON-NLS-2$
			}
		} catch (final IOException e) {
			reader.close();
			LOGGER.log(Level.SEVERE, "Error al decodificar una de las firmas resultantes", e); //$NON-NLS-1$
			throw new FIReSignatureException("Error al decodificar una de las firmas resultantes", e); //$NON-NLS-1$
		}
		reader.close();

		return result;
	}

	@Override
	public void endSign(final String transactionId) {
		// No hacemos nada
	}

	@Override
	public GenerateCertificateResult generateCertificate(final String subjectId)
																throws FIReCertificateAvailableException,
																FIReCertificateException,
																FIReConnectorNetworkException,
																FIReConnectorUnknownUserException,
																WeakRegistryException {

		final StringBuilder urlParams = new StringBuilder()
		.append(this.testUrlBase).append("TestGenerateCertificateService") //$NON-NLS-1$
		.append("?subjectid=").append(subjectId) //$NON-NLS-1$
		.append("&urlok=").append(Base64.encode(this.redirectOkUrl.getBytes(StandardCharsets.UTF_8), true)) //$NON-NLS-1$
		.append("&urlerror=").append(Base64.encode(this.redirectErrorUrl.getBytes(DEFAULT_ENCODING), true)); //$NON-NLS-1$

		byte[] response;
		try {
			response = ConnectionManager.readUrlByGet(urlParams.toString());
		} catch (final IOException e) {

			if (e instanceof HttpError) {
				if (((HttpError) e).getResponseCode() == HTTP_ERROR_UNKNOWN_USER) {
					throw new FIReConnectorUnknownUserException("El usuario no esta dado de alta en el sistema", e); //$NON-NLS-1$
				}
				else if (((HttpError) e).getResponseCode() == HTTP_ERROR_EXISTING_CERTS) {
					throw new FIReCertificateAvailableException("El usuario ya tiene certificados de firma", e); //$NON-NLS-1$
				}
				else if (((HttpError) e).getResponseCode() == HTTP_ERROR_WEAK_REGISTRY) {
					throw new WeakRegistryException("El usuario realizo un registro debil y no puede tener certificados de firma", e); //$NON-NLS-1$
				}
			}

			LOGGER.log(Level.SEVERE, "Error en la llamada al servicio de prueba de generacion de certificados", e); //$NON-NLS-1$
			throw new FIReConnectorNetworkException("Error en la llamada al servicio de generacion de certificados", e); //$NON-NLS-1$
		}

		try {
			return new GenerateCertificateResult(new String(response, StandardCharsets.UTF_8));
		} catch (final IOException e) {
			LOGGER.log(Level.SEVERE, "La respuesta del servicio de generacion de certificados no es valida:\n" + response, e); //$NON-NLS-1$
			throw new FIReCertificateException("La respuesta del servicio de generacion de certificados no es valida", e); //$NON-NLS-1$
		}
	}

	@Override
	public byte[] recoverCertificate(final String transactionId) throws FIReCertificateException, FIReConnectorNetworkException {

		final StringBuilder url = new StringBuilder()
		.append(this.testUrlBase).append("TestRecoverCertificateService") //$NON-NLS-1$
		.append("?transactionid=").append(transactionId); //$NON-NLS-1$

		byte[] response;
		try {
			response = ConnectionManager.readUrlByGet(url.toString());
		} catch (final IOException e) {
			LOGGER.log(Level.SEVERE, "Error en la llamada al servicio de recuperacion de certificado generado", e); //$NON-NLS-1$
			throw new FIReConnectorNetworkException("Error en la llamada al servicio de recuperacion de certificado generado", e); //$NON-NLS-1$
		}

		final JsonReader reader = Json.createReader(new ByteArrayInputStream(response));

		final JsonObject jsonResult = reader.readObject();
		final String result = jsonResult.getString("result"); //$NON-NLS-1$
		if (!"OK".equalsIgnoreCase(result)) { //$NON-NLS-1$
			LOGGER.log(Level.SEVERE, "El certificado de firma no se genero correctamente: " + result); //$NON-NLS-1$
			reader.close();
			throw new FIReCertificateException("El certificado de firma no se genero correctamente"); //$NON-NLS-1$
		}
		final String certEncoded = jsonResult.getString("cert"); //$NON-NLS-1$

		reader.close();

		try {
			return Base64.decode(certEncoded);
		} catch (final Exception e) {
			LOGGER.log(Level.SEVERE, "Error al decodificar el certificado de firma", e); //$NON-NLS-1$
			throw new FIReCertificateException("Error al decodificar el certificado de firma", e); //$NON-NLS-1$
		}
	}
}
