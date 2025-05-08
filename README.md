# kseq2midi

## Overview

KSEQ is a proprietary sequence file format used by Yamaha SY synthesizers. It is derived from the NSEQ file 
format (.Axx) used by the V50, TQ5, and QX5FD synthesizers.

This converter translates KSEQ song and pattern sequences (in KSEQ or ALL format) to midi and builds on top of the work previously done by Pekos Bill and animaux. 
It extends the work with support for patterns, the code was written as a Java version that can be compiled to native executables with graalvm. 

## File format

The file kseq.bt contains a binary template for 010editor. It essentially contains the file format description along 
with parsing of the relevant data. 

# Using
I have provided executables for Windows and Linux in the releases section. Mac maybe later, I don't have a real Mac.

## Building

mvn clean package -Pnative

## Tests

Besides some custom test files, I have created myself, here are some files commonly known to SY77 users that contain sequences with pattern data:
* COREA1
* AROUND_W
* BEATLES (this was actually the most difficult one to get right as it has empty patterns and different time signaures)
* TRAD (overall 2/4 time signature)

Please don't ask me where to get these. I'll ignore such questions or requests.

## Known Problems

The song time signature, time signature changes and tempo changes are not set on the midi file. Pattern time signatures are compensated for correctly. I'll try to fix this in the future.

## Future Work

I don't plan to work on this any more a lot. I know there may be possibly problems with SY99 based files, but I don't have an SY99 to test with. The code is open source, so I'm
hoping someone else will create a merge request for that if there are any issues.

## Credits
This work wasn't primarily engineered by myself. I merely extended on the work others have done before.

* Pekos Bill did the initial reverse engineering work (http://pekos.blogspot.com/2010/01/)
* Animaux continued this work by figuring out additional details, e.g. how the track data was encoded (https://animaux.de/kseq/)


