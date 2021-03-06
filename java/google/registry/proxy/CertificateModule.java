// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.proxy;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import google.registry.proxy.ProxyConfig.Environment;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.function.Function;
import javax.inject.Named;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMException;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * Dagger module that provides bindings needed to inject EPP SSL certificate chain and private key.
 *
 * <p>The production certificates and private key are stored in a .pem file that is encrypted by
 * Cloud KMS. The .pem file can be generated by concatenating the .crt certificate files on the
 * chain and the .key private file.
 *
 * <p>The production certificates in the .pem file must be stored in order, where the next
 * certificate's subject is the previous certificate's issuer.
 *
 * <p>When running the proxy locally or in test, a self signed certificate is used.
 *
 * @see <a href="https://cloud.google.com/kms/">Cloud Key Management Service</a>
 */
@Module
public class CertificateModule {

  /** Dagger qualifier to provide bindings related to EPP certificates */
  @Qualifier
  public @interface EppCertificates {}

  /** Dagger qualifier to provide bindings when running locally. */
  @Qualifier
  public @interface Local {}

  /** Dagger qualifier to provide bindings when running in production. */
  @Qualifier
  public @interface Prod {}

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  /**
   * Select specific type from a given {@link ImmutableList} and convert them using the converter.
   *
   * @param objects the {@link ImmutableList} to filter from.
   * @param clazz the class to filter.
   * @param converter the converter function to act on the items in the filtered list.
   */
  private static <T, E> ImmutableList<E> filterAndConvert(
      ImmutableList<Object> objects, Class<T> clazz, Function<T, E> converter) {
    return objects
        .stream()
        .filter(clazz::isInstance)
        .map(clazz::cast)
        .map(converter)
        .collect(toImmutableList());
  }

  @Singleton
  @Provides
  @EppCertificates
  static X509Certificate[] provideCertificates(
      Environment env,
      @Local Lazy<X509Certificate[]> localCertificates,
      @Prod Lazy<X509Certificate[]> prodCertificates) {
    return (env == Environment.LOCAL) ? localCertificates.get() : prodCertificates.get();
  }

  @Singleton
  @Provides
  @EppCertificates
  static PrivateKey providePrivateKey(
      Environment env,
      @Local Lazy<PrivateKey> localPrivateKey,
      @Prod Lazy<PrivateKey> prodPrivateKey) {
    return (env == Environment.LOCAL) ? localPrivateKey.get() : prodPrivateKey.get();
  }

  @Singleton
  @Provides
  static SelfSignedCertificate provideSelfSignedCertificate() {
    try {
      return new SelfSignedCertificate();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Singleton
  @Provides
  @Local
  static PrivateKey provideLocalPrivateKey(SelfSignedCertificate ssc) {
    return ssc.key();
  }

  @Singleton
  @Provides
  @Local
  static X509Certificate[] provideLocalCertificates(SelfSignedCertificate ssc) {
    return new X509Certificate[] {ssc.cert()};
  }

  @Singleton
  @Provides
  @Named("pemObjects")
  static ImmutableList<Object> providePemObjects(@Named("pemBytes") byte[] pemBytes) {
    PEMParser pemParser =
        new PEMParser(new InputStreamReader(new ByteArrayInputStream(pemBytes), UTF_8));
    ImmutableList.Builder<Object> listBuilder = new ImmutableList.Builder<>();
    Object obj;
    // PEMParser returns an object (private key, certificate, etc) each time readObject() is called,
    // until no more object is to be read from the file.
    while (true) {
      try {
        obj = pemParser.readObject();
        if (obj == null) {
          break;
        } else {
          listBuilder.add(obj);
        }
      } catch (IOException e) {
        throw new RuntimeException("Cannot parse PEM file correctly.", e);
      }
    }
    return listBuilder.build();
  }

  @Singleton
  @Provides
  @Prod
  static PrivateKey provideProdPrivateKey(@Named("pemObjects") ImmutableList<Object> pemObjects) {
    JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
    Function<PEMKeyPair, PrivateKey> privateKeyConverter =
        pemKeyPair -> {
          try {
            return converter.getKeyPair(pemKeyPair).getPrivate();
          } catch (PEMException e) {
            throw new RuntimeException(
                String.format("Error converting private key: %s", pemKeyPair), e);
          }
        };
    ImmutableList<PrivateKey> privateKeys =
        filterAndConvert(pemObjects, PEMKeyPair.class, privateKeyConverter);
    checkState(
        privateKeys.size() == 1,
        "The pem file must contain exactly one private key, but %s keys are found",
        privateKeys.size());
    return privateKeys.get(0);
  }

  @Singleton
  @Provides
  @Prod
  static X509Certificate[] provideProdCertificates(
      @Named("pemObjects") ImmutableList<Object> pemObject) {
    JcaX509CertificateConverter converter = new JcaX509CertificateConverter().setProvider("BC");
    Function<X509CertificateHolder, X509Certificate> certificateConverter =
        certificateHolder -> {
          try {
            return converter.getCertificate(certificateHolder);
          } catch (CertificateException e) {
            throw new RuntimeException(
                String.format("Error converting certificate: %s", certificateHolder), e);
          }
        };
    ImmutableList<X509Certificate> certificates =
        filterAndConvert(pemObject, X509CertificateHolder.class, certificateConverter);
    checkState(certificates.size() != 0, "No certificates found in the pem file");
    X509Certificate lastCert = null;
    for (X509Certificate cert : certificates) {
      if (lastCert != null) {
        checkState(
            lastCert.getIssuerX500Principal().equals(cert.getSubjectX500Principal()),
            "Certificate chain error:\n%s\nis not signed by\n%s",
            lastCert,
            cert);
      }
      lastCert = cert;
    }
    X509Certificate[] certificateArray = new X509Certificate[certificates.size()];
    certificates.toArray(certificateArray);
    return certificateArray;
  }
}
