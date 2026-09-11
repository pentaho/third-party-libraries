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
package org.pentaho.hadoop.shaded.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.codec.smtp.SmtpResponseDecoder;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the security posture of the Netty release embedded in {@code pentaho-shaded-netty}.
 *
 * <p>These assertions exercise the Netty modules bundled by {@code netty-all}, which is what the
 * shade plugin relocates into the published artifact. They fail on Netty 4.1.137.Final and pass on
 * 4.1.138.Final, so they are a real regression guard rather than a restatement of the version
 * property.</p>
 */
class EmbeddedNettySecurityFixTest {

  /**
   * Netty's default bound on the accumulated size of a multi-line SMTP response
   * ({@code SmtpResponseDecoder.DEFAULT_MAX_RESPONSE_SIZE}). The field is private, so the value is
   * mirrored here; the tests below straddle it from both sides.
   */
  private static final int DEFAULT_MAX_RESPONSE_SIZE = 64 * 1024;

  /** OID id-kp-OCSPSigning -- the Extended Key Usage an OCSP responder certificate must carry. */
  private static final String ID_KP_OCSP_SIGNING_OID = "1.3.6.1.5.5.7.3.9";

  private static EmbeddedChannel smtpChannel() {
    return new EmbeddedChannel( new SmtpResponseDecoder( 1024 ) );
  }

  private static ByteBuf ascii( String s ) {
    return Unpooled.copiedBuffer( s, CharsetUtil.US_ASCII );
  }

  /**
   * An unterminated multi-line SMTP response must not be buffered without bound.
   *
   * <p>A hostile or broken SMTP server can emit {@code 250-...} continuation lines forever. Before
   * the fix the decoder accumulated every line into an unbounded list, so the peer could drive the
   * client out of memory. The decoder must now fail the stream once the accumulated response
   * exceeds its cap.</p>
   */
  @Test
  void unterminatedMultiLineSmtpResponseIsBounded() {
    EmbeddedChannel channel = smtpChannel();

    // Each continuation line is charged its own length plus a per-entry overhead, so feeding well
    // past the cap in raw bytes is comfortably past it in accounted size too.
    String line = "250-" + repeat( 'x', 200 ) + "\r\n";
    int lines = ( ( DEFAULT_MAX_RESPONSE_SIZE / 200 ) + 64 );

    DecoderException thrown = assertThrows( DecoderException.class, () -> {
      for ( int i = 0; i < lines; i++ ) {
        channel.writeInbound( ascii( line ) );
      }
    }, "an unbounded multi-line SMTP response was accepted -- the embedded Netty is missing the "
        + "SmtpResponseDecoder response-size bound (fixed in 4.1.138.Final)" );

    assertTrue( thrown instanceof TooLongFrameException || thrown.getCause() instanceof TooLongFrameException,
        "expected the bound to be reported as a TooLongFrameException but was: " + thrown );

    channel.finishAndReleaseAll();
  }

  /**
   * Negative control for {@link #unterminatedMultiLineSmtpResponseIsBounded()}.
   *
   * <p>Without this, a decoder that rejected <em>every</em> multi-line response would pass the guard
   * above. A normal, well-formed multi-line response must still decode.</p>
   */
  @Test
  void wellFormedMultiLineSmtpResponseStillDecodes() {
    EmbeddedChannel channel = smtpChannel();

    assertTrue( channel.writeInbound( ascii( "250-mx.example.com\r\n250-PIPELINING\r\n250 STARTTLS\r\n" ) ),
        "a well-formed multi-line SMTP response should have produced a decoded message" );

    SmtpResponse response = channel.readInbound();
    assertNotNull( response, "no SmtpResponse was decoded" );
    assertEquals( 250, response.code() );
    assertEquals( 3, response.details().size() );

    channel.finishAndReleaseAll();
  }

  /**
   * A single line longer than {@code maxLineLength} must still be rejected.
   *
   * <p>The response-size bound is an addition to, not a replacement for, the pre-existing per-line
   * limit; this pins that the two coexist.</p>
   */
  @Test
  void overlongSingleSmtpLineIsStillRejected() {
    EmbeddedChannel channel = smtpChannel();

    assertThrows( DecoderException.class,
        () -> channel.writeInbound( ascii( "250-" + repeat( 'y', 4096 ) + "\r\n" ) ),
        "a line beyond maxLineLength should still be rejected" );

    channel.finishAndReleaseAll();
  }

  /**
   * The OCSP client must verify the id-kp-OCSPSigning Extended Key Usage on a delegated responder
   * certificate; without it, any certificate signed by the issuer can forge a "good" status and
   * defeat revocation checking.
   *
   * <p>The check lives in code reached only by a full OCSP exchange against a live responder, which
   * is not something this repackaging module can stand up. The guard therefore asserts on the
   * compiled class itself: the OID and the rejection message appear in the constant pool of
   * {@code OcspClient} only once the check exists. A negative control on an OID that must never be
   * present keeps the assertion from passing vacuously.</p>
   */
  @Test
  void ocspResponderCertificateEkuIsVerified() throws Exception {
    String classBytes = constantPoolText( "io/netty/handler/ssl/ocsp/OcspClient.class" );

    assertTrue( classBytes.contains( ID_KP_OCSP_SIGNING_OID ),
        "OcspClient does not reference id-kp-OCSPSigning (" + ID_KP_OCSP_SIGNING_OID
            + ") -- the embedded Netty is missing the OCSP responder EKU check (fixed in 4.1.138.Final)" );
    assertTrue( classBytes.contains( "OCSP Responder is not authorized to sign OCSP responses" ),
        "the OCSP responder authorization failure path is absent from the embedded Netty" );

    // Negative control: an OID that is not part of this check must not be found, proving the
    // assertion above is actually reading the constant pool and not matching everything.
    assertFalse( classBytes.contains( "1.3.6.1.5.5.7.3.99" ),
        "constant-pool scan matched an OID that should not be present -- the guard is vacuous" );
  }

  private static String constantPoolText( String resource ) throws Exception {
    try ( InputStream in = EmbeddedNettySecurityFixTest.class.getClassLoader().getResourceAsStream( resource ) ) {
      assertNotNull( in, "class not found on the test classpath: " + resource );
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[ 8192 ];
      int read;
      while ( ( read = in.read( buf ) ) != -1 ) {
        out.write( buf, 0, read );
      }
      return new String( out.toByteArray(), StandardCharsets.ISO_8859_1 );
    }
  }

  private static String repeat( char c, int times ) {
    StringBuilder sb = new StringBuilder( times );
    for ( int i = 0; i < times; i++ ) {
      sb.append( c );
    }
    return sb.toString();
  }
}
