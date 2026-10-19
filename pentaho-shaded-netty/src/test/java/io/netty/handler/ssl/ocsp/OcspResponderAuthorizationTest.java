/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2026 by Pentaho Canada Inc. : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2030-06-15
 ******************************************************************************/
package io.netty.handler.ssl.ocsp;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.jcajce.JcaRespID;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural guard for the OCSP delegated-responder authorization check.
 *
 * <p>This test lives in Netty's own package so it can call the package-private
 * {@link OcspClient#validateSignature(BasicOCSPResp, X509Certificate)} directly, and it drives that
 * method with real certificates and a real signed OCSP response rather than inspecting bytecode.</p>
 *
 * <p>The vulnerability: when a CA delegates OCSP signing, the responder certificate must carry the
 * {@code id-kp-OCSPSigning} Extended Key Usage. Netty 4.1.137.Final verified only that the responder
 * certificate chained to the issuer, so <em>any</em> end-entity certificate signed by that CA --
 * including an ordinary server certificate an attacker legitimately owns -- could sign a forged
 * "good" status and silently defeat revocation checking. 4.1.138.Final rejects such a responder.</p>
 *
 * <p>{@code validateSignature} has an identical signature on both releases, so this test compiles
 * against the vulnerable version and fails there behaviourally -- which is what makes it a
 * regression guard and not a restatement of the version property.</p>
 */
class OcspResponderAuthorizationTest {

  private static final String SIG_ALG = "SHA256withRSA";

  @BeforeAll
  static void installProvider() {
    if ( Security.getProvider( BouncyCastleProvider.PROVIDER_NAME ) == null ) {
      Security.addProvider( new BouncyCastleProvider() );
    }
  }

  /**
   * A delegated responder whose certificate lacks id-kp-OCSPSigning must be rejected.
   *
   * <p>Fails on 4.1.137.Final, where the forged response is accepted.</p>
   */
  @Test
  void responderWithoutOcspSigningEkuIsRejected() throws Exception {
    Fixture fixture = Fixture.create( false );

    OCSPException thrown = assertThrows( OCSPException.class,
        () -> OcspClient.validateSignature( fixture.response, fixture.issuerCertificate ),
        "an OCSP response signed by a delegated responder that lacks id-kp-OCSPSigning was accepted "
            + "-- the embedded Netty is missing the responder authorization check (fixed in 4.1.138.Final). "
            + "Any certificate issued by the same CA could forge a 'good' revocation status." );

    assertTrue( thrown.getMessage().contains( "not authorized" ),
        "the response was rejected, but not because the responder is unauthorized: " + thrown.getMessage() );
  }

  /**
   * Negative control: the same exchange with a properly authorized responder must be accepted.
   *
   * <p>Without this, an implementation that rejected every delegated responder -- or a fixture so
   * malformed that validation always failed -- would satisfy the guard above.</p>
   */
  @Test
  void responderWithOcspSigningEkuIsAccepted() throws Exception {
    Fixture fixture = Fixture.create( true );

    assertDoesNotThrow( () -> OcspClient.validateSignature( fixture.response, fixture.issuerCertificate ),
        "a correctly authorized delegated OCSP responder was rejected -- the guard above would pass "
            + "vacuously because every responder is refused" );
  }

  /** A CA, a delegated responder it issued, and an OCSP response signed by that responder. */
  private static final class Fixture {
    private final X509Certificate issuerCertificate;
    private final BasicOCSPResp response;

    private Fixture( X509Certificate issuerCertificate, BasicOCSPResp response ) {
      this.issuerCertificate = issuerCertificate;
      this.response = response;
    }

    static Fixture create( boolean authorizeResponder ) throws Exception {
      KeyPairGenerator generator = KeyPairGenerator.getInstance( "RSA" );
      generator.initialize( 2048 );

      KeyPair issuerKeyPair = generator.generateKeyPair();
      KeyPair responderKeyPair = generator.generateKeyPair();

      Date notBefore = new Date( System.currentTimeMillis() - TimeUnit.DAYS.toMillis( 1 ) );
      Date notAfter = new Date( System.currentTimeMillis() + TimeUnit.DAYS.toMillis( 365 ) );

      X500Name issuerName = new X500Name( "CN=CodeMedic Test CA" );
      X500Name responderName = new X500Name( "CN=CodeMedic Test OCSP Responder" );

      // Self-signed CA.
      JcaX509v3CertificateBuilder issuerBuilder = new JcaX509v3CertificateBuilder(
          issuerName, BigInteger.ONE, notBefore, notAfter, issuerName, issuerKeyPair.getPublic() );
      issuerBuilder.addExtension( Extension.basicConstraints, true, new BasicConstraints( 0 ) );
      X509Certificate issuerCertificate =
          toCertificate( issuerBuilder.build( signer( issuerKeyPair.getPrivate() ) ) );

      // Responder certificate, signed by the CA above. The EKU is the whole point of the test: with
      // it the responder is a legitimate delegate; without it it is just an end-entity certificate
      // that happens to share an issuer -- which is exactly the attacker's position.
      JcaX509v3CertificateBuilder responderBuilder = new JcaX509v3CertificateBuilder(
          issuerName, BigInteger.valueOf( 2 ), notBefore, notAfter, responderName,
          responderKeyPair.getPublic() );
      responderBuilder.addExtension( Extension.basicConstraints, true, new BasicConstraints( false ) );
      if ( authorizeResponder ) {
        responderBuilder.addExtension( Extension.extendedKeyUsage, false,
            new ExtendedKeyUsage( KeyPurposeId.id_kp_OCSPSigning ) );
      }
      X509CertificateHolder responderHolder = responderBuilder.build( signer( issuerKeyPair.getPrivate() ) );
      X509Certificate responderCertificate = toCertificate( responderHolder );

      // A "good" status response for an arbitrary serial, signed by the responder and carrying its
      // own certificate -- the shape a delegated responder actually returns.
      DigestCalculator digestCalculator = new JcaDigestCalculatorProviderBuilder()
          .setProvider( BouncyCastleProvider.PROVIDER_NAME ).build()
          .get( CertificateID.HASH_SHA1 );
      CertificateID certificateId = new CertificateID( digestCalculator,
          new X509CertificateHolder( issuerCertificate.getEncoded() ), BigInteger.valueOf( 4242 ) );

      BasicOCSPRespBuilder responseBuilder =
          new BasicOCSPRespBuilder( new JcaRespID( responderCertificate.getSubjectX500Principal() ) );
      responseBuilder.addResponse( certificateId, CertificateStatus.GOOD );

      BasicOCSPResp response = responseBuilder.build( signer( responderKeyPair.getPrivate() ),
          new X509CertificateHolder[] { responderHolder }, new Date() );

      return new Fixture( issuerCertificate, response );
    }

    private static ContentSigner signer( PrivateKey key ) throws Exception {
      return new JcaContentSignerBuilder( SIG_ALG ).setProvider( BouncyCastleProvider.PROVIDER_NAME ).build( key );
    }

    private static X509Certificate toCertificate( X509CertificateHolder holder ) throws Exception {
      return new JcaX509CertificateConverter().setProvider( BouncyCastleProvider.PROVIDER_NAME )
          .getCertificate( holder );
    }
  }
}
