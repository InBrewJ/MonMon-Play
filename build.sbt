val playPac4jVersion = "11.0.0-PLAY2.8-RC2-SNAPSHOT"
val pac4jVersion = "5.0.0-RC2-SNAPSHOT"
val playVersion = "2.8.7"
val guiceVersion = "4.2.2"

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)
  .settings(
    name := """MonMon Play (Java)""",
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.13.1",
    libraryDependencies ++= Seq(
      guice,
      javaJpa,
      ehcache,
      "com.h2database" % "h2" % "1.4.199",
      "org.hibernate" % "hibernate-core" % "5.4.9.Final",
      javaWs % "test",
      "org.awaitility" % "awaitility" % "4.0.1" % "test",
      "org.assertj" % "assertj-core" % "3.14.0" % "test",
      "org.mockito" % "mockito-core" % "3.1.0" % "test",
      "org.pac4j" %% "play-pac4j" % playPac4jVersion,
      "org.pac4j" % "pac4j-http" % pac4jVersion excludeAll(ExclusionRule(organization = "com.fasterxml.jackson.core")),
      "org.pac4j" % "pac4j-oidc" % pac4jVersion  excludeAll(ExclusionRule("commons-io" , "commons-io"), ExclusionRule(organization = "com.fasterxml.jackson.core")),
      "com.typesafe.play" % "play-cache_2.13" % playVersion,
      "commons-io" % "commons-io" % "2.8.0",
      //For Java > 8
      "javax.xml.bind" % "jaxb-api" % "2.3.1",
      "javax.annotation" % "javax.annotation-api" % "1.3.2",
      "javax.el" % "javax.el-api" % "3.0.0",
      "org.glassfish" % "javax.el" % "3.0.0"

    ),
    Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v"),
    scalacOptions ++= List("-encoding", "utf8", "-deprecation", "-feature", "-unchecked"),
    javacOptions ++= List("-Xlint:unchecked", "-Xlint:deprecation", "-Werror"),
    PlayKeys.externalizeResourcesExcludes += baseDirectory.value / "conf" / "META-INF" / "persistence.xml"
  )

resolvers ++= Seq(
  Resolver.mavenLocal,
  "Sonatype snapshots repository" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Shibboleth releases" at "https://build.shibboleth.net/nexus/content/repositories/releases/"
)
