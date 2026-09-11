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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the security posture of the artifact this module actually publishes.
 *
 * <p>The unit tests run in the {@code test} phase, against the {@code netty-all} dependency on the
 * classpath -- i.e. the shade plugin's <em>input</em>. They cannot see a packaging regression: a
 * broken relocation pattern, an over-eager include/exclude filter, or a stale attached artifact
 * could leave the published jar carrying vulnerable classes while every unit test still passed.</p>
 *
 * <p>This integration test runs in the {@code verify} phase and opens the produced jar, asserting
 * that the <em>relocated</em> classes carry the 4.1.138.Final fixes and that the relocation is
 * complete. It closes the gap between "the dependency is fixed" and "what we ship is fixed".</p>
 */
class ShadedArtifactSecurityIT {

  /** Package prefix the shade plugin relocates {@code io.netty} to, for HBase compatibility. */
  private static final String RELOCATED = "org/apache/hbase/thirdparty/io/netty/";

  /** OID id-kp-OCSPSigning -- present only once the responder authorization check exists. */
  private static final String ID_KP_OCSP_SIGNING_OID = "1.3.6.1.5.5.7.3.9";

  private static File shadedJar() {
    String path = System.getProperty( "shaded.artifact" );
    assertNotNull( path, "the 'shaded.artifact' system property was not supplied by failsafe" );
    File jar = new File( path );
    assertTrue( jar.isFile(), "the shaded artifact was not produced at: " + jar.getAbsolutePath() );
    return jar;
  }

  /**
   * The relocated OCSP client in the published jar must carry the responder authorization check.
   */
  @Test
  void publishedJarCarriesOcspResponderAuthorizationCheck() throws Exception {
    String bytes = entryText( RELOCATED + "handler/ssl/ocsp/OcspClient.class" );

    assertTrue( bytes.contains( ID_KP_OCSP_SIGNING_OID ),
        "the relocated OcspClient in the published jar does not reference id-kp-OCSPSigning ("
            + ID_KP_OCSP_SIGNING_OID + ") -- the shipped artifact is missing the OCSP responder "
            + "authorization check even though the dependency has it" );
    assertTrue( bytes.contains( "OCSP Responder is not authorized to sign OCSP responses" ),
        "the responder authorization failure path is absent from the published jar" );
  }

  /**
   * The relocated SMTP decoder in the published jar must carry the response-size bound.
   */
  @Test
  void publishedJarCarriesSmtpResponseSizeBound() throws Exception {
    String bytes = entryText( RELOCATED + "handler/codec/smtp/SmtpResponseDecoder.class" );

    assertTrue( bytes.contains( "SMTP response exceeds " ),
        "the relocated SmtpResponseDecoder in the published jar has no response-size bound -- the "
            + "shipped artifact is missing the unbounded-buffering fix" );
  }

  /**
   * The relocation must be complete: no unrelocated {@code io/netty} classes may leak into the jar.
   *
   * <p>A partial relocation would mean consumers load a second, unguarded copy of Netty, and the
   * two assertions above would still pass.</p>
   */
  @Test
  void relocationIsComplete() throws Exception {
    try ( JarFile jar = new JarFile( shadedJar() ) ) {
      int relocated = 0;
      for ( java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
        String name = e.nextElement().getName();
        if ( name.startsWith( "io/netty/" ) && name.endsWith( ".class" ) ) {
          fail( "the published jar contains an unrelocated Netty class: " + name
              + " -- the shade relocation is incomplete and consumers would load an unguarded copy" );
        }
        if ( name.startsWith( RELOCATED ) && name.endsWith( ".class" ) ) {
          relocated++;
        }
      }
      assertTrue( relocated > 1000,
          "only " + relocated + " relocated Netty classes were found; the jar looks truncated or the "
              + "relocation prefix changed, so the assertions above prove nothing" );
    }
  }

  /**
   * The manifest must record the Netty version actually bundled, so the shipped jar is
   * self-describing for downstream scanners.
   */
  @Test
  void manifestRecordsBundledNettyVersion() throws Exception {
    String expected = System.getProperty( "netty.version" );
    assertNotNull( expected, "the 'netty.version' system property was not supplied by failsafe" );

    try ( JarFile jar = new JarFile( shadedJar() ) ) {
      Manifest manifest = jar.getManifest();
      assertNotNull( manifest, "the published jar has no manifest" );
      Attributes attributes = manifest.getMainAttributes();
      assertEquals( expected, attributes.getValue( "Shaded-Netty-Version" ),
          "the manifest does not record the Netty version that was actually bundled" );
    }
  }

  private static String entryText( String entryName ) throws Exception {
    try ( JarFile jar = new JarFile( shadedJar() ) ) {
      ZipEntry entry = jar.getEntry( entryName );
      assertNotNull( entry, "the published jar does not contain " + entryName
          + " -- the relocation prefix or the shade include/exclude filters have changed" );
      try ( InputStream in = jar.getInputStream( entry ) ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[ 8192 ];
        int read;
        while ( ( read = in.read( buffer ) ) != -1 ) {
          out.write( buffer, 0, read );
        }
        return new String( out.toByteArray(), StandardCharsets.ISO_8859_1 );
      }
    }
  }
}
