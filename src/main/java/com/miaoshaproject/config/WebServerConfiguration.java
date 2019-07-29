package com.miaoshaproject.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;
//当spring容器内没有TomcatEmbededServletContainerFactory这个bean时，会把bean加载进spring容器
@Component
public class WebServerConfiguration implements WebServerFactoryCustomizer<ConfigurableWebServerFactory> {
    @Override
    public void customize(ConfigurableWebServerFactory configurableWebServerFactory) {
    //使用对应工厂提供的接口定制化自己的tomcat connector
        ((TomcatServletWebServerFactory)configurableWebServerFactory).addConnectorCustomizers(
                new TomcatConnectorCustomizer(){

                    @Override
                    public void customize(Connector connector) {
                        //
                        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
                        //订制化keepalivetimeout设置30秒内没有请求则服务器自动断开keepalive链接
                        protocol.setKeepAliveTimeout(30000);
                        //当客户发送端发送超过10000个请求则自动断开keepalive链接
                        protocol.setMaxKeepAliveRequests(10000);

                    }
                });
    }
}
