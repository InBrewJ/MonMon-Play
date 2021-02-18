# MonMon-Play

- A port of my personal finance GSheet using the Play Framework for Java using:
    - Java 11
    - Play 2.8.7
    - Pac4j 5.0.0
    - Keycloak 12.0.2
    - And, eventually, PostgreSQL! (for now, everything is within a h2 database)
    
- Hard dependencies:
    - Java 9
    - Docker
    - sbt
    - The will to tinker

## How to run locally

- Start up Keycloak

```
./run_boh.sh
```

- Provision Keycloak

```
cd auth
./realm_setup.sh
cd -
```

- (Missing step) Add users to the realm

- Run the Play app

```
sbt run
```



## Auth

- For my sins, auth is handled with Keycloak at present
- Start a local Keycloak through docker with:

```bash
docker run -it -p 8080:8080 -e KEYCLOAK_USER=admin -e KEYCLOAK_PASSWORD=admin -e DB_VENDOR=h2 quay.io/keycloak/keycloak:12.0.2
```

- This will run Keycloak against an in-memory h2 database
- Quickstarted with:
  - https://www.keycloak.org/getting-started/getting-started-docker
- To get the client secret:
  - https://stackoverflow.com/questions/44752273/do-keycloak-clients-have-a-client-secret
  - set client access type to 'confidential'

### Essentials for making OIDC work (with client/secret auth)

- Set the authentication method

```java
 oidcConfiguration.setClientAuthenticationMethod(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC
        );
```

- Set the preferred JWS algorithm:

```java
 oidcConfiguration.setPreferredJwsAlgorithm(JWSAlgorithm.RS256);
```

- Then, set the same algo in Keycloak (12):

  - realm ->
    client ->
    settings ->
    Fine Grain OpenID Connect Configuration ->
    Access Token Signature Algorithm ->
    "RS256"

- Also add this library to build.sbt. This fixes a runtime decoding problem

```
"javax.xml.bind" % "jaxb-api" % "2.3.0"
```

- https://stackoverflow.com/questions/46381242/intellij-sbt-based-scala-project-does-not-build-with-java-9

### Useful links

- https://openid.net/connect/
- https://developer.okta.com/blog/2017/10/31/add-authentication-to-play-framework-with-oidc
- https://www.pac4j.org/docs/clients/openid-connect.html
- https://medium.com/sqooba/securing-a-single-page-app-through-openid-connect-sso-using-an-explicit-flow-with-play2-and-pac4j-7f6c4f46e31a
- https://github.com/pac4j/pac4j/issues/1189

# Extends :: play-java-jpa-example

This project demonstrates how to create a simple database application with Play, using JPA.

Please see the Play documentation for more details:

- https://www.playframework.com/documentation/latest/JavaJPA
- https://www.playframework.com/documentation/latest/ThreadPools
- https://www.playframework.com/documentation/latest/JavaAsync

```

```
