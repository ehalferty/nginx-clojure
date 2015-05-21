# nginx-tomcat

A Java library designed to embed Tomcat into Nginx by Nignx-Clojure Module so that Nginx can  Support Java Standard Web Applications.

## Usage

To get nginx-tomcat8-x.x.x.jar

```shell
lein jar
```

```nginx
      location / {
      
          content_handler_name 'nginx.clojure.bridge.NginxBridgeHandler';
          content_handler_property system.catalina.home '/home/who/share/apps/apache-tomcat-8.0.20';
          content_handler_property system.catalina.base '#{catalina.home}';
          content_handler_property system.org.apache.tomcat.websocket.DISABLE_BUILTIN_EXTENSIONS true;
          content_handler_property system.java.util.logging.manager 'org.apache.juli.ClassLoaderLogManager';
          content_handler_property bridge.lib.dirs '#{catalina.home}/lib:#{catalina.home}/bin';
          #set nginx tomcat8 bridge implementation jar
          content_handler_property bridge.lib.cp 'my-jar-path/nginx-tomcat8-x.x.x.jar';
          content_handler_property bridge.imp 'nginx.clojure.tomcat8.NginxTomcatBridgeImpl';
          gzip on;
          gzip_types text/plain text/css 'text/html;charset=UTF-8'; 
      }
```

## For performance

### Diable Tomcat Access Log

in server.xml comment AccessLogValve configuration 

```
<!--
        <Valve className="org.apache.catalina.valves.AccessLogValve" directory="logs"
               prefix="localhost_access_log" suffix=".txt"
               pattern="%h %l %u %t &quot;%r&quot; %s %b" />
-->
```


## License

Copyright © 2013-2015 Zhang, Yuexiang (xfeep) and released under the BSD 3-Clause license.