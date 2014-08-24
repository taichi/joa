# JVM option analyzer

this script works...

* get source releases from java.net
    * [java6](http://download.java.net/openjdk/jdk6/)
    * [java7](http://download.java.net/openjdk/jdk7/)
    * [java8](http://download.java.net/openjdk/jdk8/)
* parse jvm options from source codes
* output all of jvm options to json and csv
    * csv is look like [this](https://docs.google.com/spreadsheets/d/1W2enGLRz0t7PQ75nZn2QGZb-dyOrsjNflMNQZUyc5Tg/edit?usp=sharing)

if you want to get source relelases automatically, execute below

    gradlew getSrcs

if you want to parse source codes

    gradlew analyzeOptions


run all at once

    gradlew

# License
Apache License, Version 2.0

