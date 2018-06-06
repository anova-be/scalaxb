Anova fork
==========
The aim of this fork is to leverage scalaxb to generate **jaxb** scala case classes.
This means adding annotations to the classes and parameters, and suppressing all scala.xml related code. 

If we can make it generate 90% of our data model, a large (maintenance) effort is saved in creating our internal data model for our [politie-broker](https://github.com/anova-be/politie-broker) repository

http://krasserm.blogspot.be/2012/02/using-jaxb-for-xml-and-json-apis-in.html
http://caoilte.org/scala/2014/09/29/bending-jaxb-to-the-will-of-scala-1-of-2/
http://blog.bdoughan.com/2010/11/jaxb-and-inheritance-using-substitution.html

### Usage

#### Core

```
$ cd .
$ sbt "project app" "publishM2"
```

#### Mvn plugin
```
$ cd mvn-scalaxb
$ mvn clean install
```

### TODO

- Clean generation of default values for parameters: remove comments, logging, ...

scalaxb
=======

![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.scalaxb/scalaxb_2.12/badge.svg)

[![Join the chat at https://gitter.im/eed3si9n/scalaxb](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/eed3si9n/scalaxb?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

scalaxb is an XML data-binding tool for Scala that supports W3C XML Schema (xsd) and
Web Services Description Language (wsdl) as the input file.

From schema documents scalaxb will generate Scala source files containing
case classes to represent the data and typeclass instances to turn XML documents into an object,
and the object back to XML.

Modules
-------

There are currently four ways of running scalaxb:

- command line app `scalaxb`
- sbt plugin sbt-scalaxb
- maven plugin mvn-scalaxb
- web API scalaxb-heroku hosted on heroku

### sbt-scalaxb

To call scalaxb from sbt 1.x and sbt 0.13.x, put this in your `project/scalaxb.sbt`:

    resolvers += Resolver.sonatypeRepo("public")
    addSbtPlugin("org.scalaxb" % "sbt-scalaxb" % "X.X")

and this in `scalaxb.sbt`:

```scala
lazy val root = (project in file(".")).
  enablePlugins(ScalaxbPlugin).
  settings(
    name := "foo-project",
    scalaxbPackageName in (Compile, scalaxb) := "generated",
    // scalaxbAutoPackages in (Compile, scalaxb) := true
    scalaxbDispatchVersion in (Compile, scalaxb) := "0.11.3"
  )
```

### command line app scalaxb

See [INSTALL.md][1].

### mvn-scalaxb

See [mvn-scalaxb][2].

Documents
---------

Further info is available at [scalaxb.org](http://scalaxb.org/).

Bug Reporting
-------------

If you're having problem with scalaxb, please take a moment and read [issue reporting guideline][3].

Licensing
---------

It's the MIT License. See the file called LICENSE.

Contacts
--------

- [mailing list](http://groups.google.com/group/scalaxb)
- [@scalaxb](http://twitter.com/scalaxb)

  [1]: https://github.com/eed3si9n/scalaxb/blob/master/INSTALL.md
  [2]: http://scalaxb.org/mvn-scalaxb
  [3]: http://scalaxb.org/issue-reporting-guideline
