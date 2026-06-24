#!/bin/sh
# Launch the Election Ballot System (SQLite, port 8080)
java -Xmx512m \
     -Dspring.profiles.active=sqlite \
     -Dserver.port=8080 \
     -jar election-ballot-system-1.0.0.jar
