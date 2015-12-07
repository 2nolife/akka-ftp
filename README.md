# Akka-FTP

The Akka-FTP project is the reactive FTP server which uses Akka actors under the hood. It also provides the AngularJS web dashboard with Spray in the backend. Version 1.0 and the documantation expected to be released in December 2015

For details check out the [Akka-FTP blog](http://akka-ftp.blogspot.co.uk)

## Running the project ##

You need [SBT](http://www.scala-sbt.org) to build and run the server. Clone the repository and from the project directory `sbt run`

```
$ sbt run
[info] ...
[info] Running com.coldcore.akkaftp.Launcher
```

To stop the server either `^C` or send a GET request to [http://localhost:2080/api/action/shutdown](http://localhost:2080/api/action/shutdown)

## Big thank you ##

[ColoradoFTP](https://bitbucket.org/nolife/coloradoftp) for the FTP commands and ideas

[Gabbler](https://github.com/hseeberger/gabbler) the excellent example for Akka, Spray and AngularJS

## Contribution policy ##

Contributions via GitHub pull requests are gladly accepted from their original author. Along with any pull requests, please state that the contribution is your original work and that you license the work to the project under the project's open source license. Whether or not you state this explicitly, by submitting any copyrighted material via pull request, email, or other means you agree to license the material under the project's open source license and warrant that you have the legal authority to do so.

## License ##

This code is open source software licensed under the [GNU Lesser General Public License v3](http://www.gnu.org/licenses/lgpl-3.0.en.html).
