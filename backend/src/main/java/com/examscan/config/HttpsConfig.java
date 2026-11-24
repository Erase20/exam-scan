package com.examscan.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 可选 HTTPS 附加端口（默认 8443）。
 *
 * 手机拍照的实时取景（绿色线框）依赖浏览器 getUserMedia，而浏览器只允许在
 * HTTPS（或 localhost）环境下调用摄像头——通过 http://IP:8091 访问时会被直接禁用，
 * 组件被迫降级为系统相机（无法叠加线框）。
 *
 * 启动参数加 --exam-scan.https.enabled=true 且证书文件存在时，在原 HTTP 端口之外
 * 额外开启一个 HTTPS 端口，两者同时可用；手机改用 https://IP:8443 访问即可实时取景。
 * 自签证书首次访问时浏览器会提示不安全，选择「高级 → 继续访问」即可。
 */
@Configuration
@ConditionalOnProperty(name = "exam-scan.https.enabled", havingValue = "true")
public class HttpsConfig {

    @Value("${exam-scan.https.port:8443}")
    private int port;

    @Value("${exam-scan.https.keystore:./data/https.p12}")
    private String keystore;

    @Value("${exam-scan.https.password:examscan}")
    private String password;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpsConnectorCustomizer() {
        return factory -> {
            if (!Files.exists(Path.of(keystore))) {
                return;   // 证书不存在时静默跳过，HTTP 照常可用
            }
            Connector connector = new Connector(Http11NioProtocol.class.getName());
            connector.setPort(port);
            connector.setScheme("https");
            connector.setSecure(true);
            Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
            protocol.setSSLEnabled(true);
            SSLHostConfig ssl = new SSLHostConfig();
            SSLHostConfigCertificate cert = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
            cert.setCertificateKeystoreFile(Path.of(keystore).toAbsolutePath().toString());
            cert.setCertificateKeystorePassword(password);
            cert.setCertificateKeystoreType("PKCS12");
            ssl.addCertificate(cert);
            protocol.addSslHostConfig(ssl);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
