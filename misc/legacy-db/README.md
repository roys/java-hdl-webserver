# Legacy database fixtures

`src/test/resources/legacy/hdlserver-<version>.db.gz` are `hdlserver.db` files written by the released JARs
themselves, so the tests can prove that a new version still reads what earlier versions stored (the auth key, actions
with their IDs and aliases, and one component of every kind).

They were made with `MakeLegacyDb.java`, compiled against and run with a released JAR. The old JARs are no longer in
`releases/`, but git still has them:

```sh
git show v1.1.0:releases/old/hdlserver-1.0.0.jar > /tmp/hdlserver-1.0.0.jar
javac --release 8 -cp /tmp/hdlserver-1.0.0.jar -d /tmp/legacy-db misc/legacy-db/MakeLegacyDb.java
java -cp /tmp/legacy-db:/tmp/hdlserver-1.0.0.jar MakeLegacyDb /tmp/hdlserver-1.0.0.db
gzip -9 -n -c /tmp/hdlserver-1.0.0.db > src/test/resources/legacy/hdlserver-1.0.0.db.gz
```

To also check a real database, without adding it to the repo:

```sh
./gradlew test --rerun -Dhdlserver.legacyDb=/path/to/hdlserver.db
```
