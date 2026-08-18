# Pentaho Shaded Netty

## What This Module Does

This module creates a **custom shaded Netty JAR** that:

1. Packages the Netty version configured in `pom.xml`
2. Relocates all Netty packages to match HBase's expected namespace
3. Replaces the `hbase-shaded-netty:4.1.10` dependency

## Package Relocation

```bash
Original Netty:     io.netty.*
Relocated to:       org.apache.hadoop.hbase.thirdparty.io.netty.*
```

This matches exactly what Apache HBase's `hbase-shaded-netty` does, ensuring **100% compatibility** with HBase client code.

## Version Details

- **Upstream Netty Version**: `<netty-version>` configured in `pom.xml`
- **Pentaho Wrapper Version**: `<netty-version>-pentaho-<revision>`
- **Old Dependency**: `hbase-shaded-netty:4.1.10`
- **New Dependency**: `pentaho-shaded-netty` containing the configured Netty version

The JAR records the embedded Netty version as `Shaded-Netty-Version`. Dependabot
checks `io.netty:netty-all` weekly for patch updates.

## Why This Approach Works

### ✅ Works With Other 6 HBase ThirdParty JARs

The 6 other HBase ThirdParty JARs remain at version 4.1.10:

- `hbase-shaded-gson`
- `hbase-shaded-jersey`
- `hbase-shaded-jetty`
- `hbase-shaded-miscellaneous`
- `hbase-shaded-protobuf`
- `hbase-unsafe`

**Why it works:**

- Each shaded JAR is **independent** - they don't depend on each other
- `hbase-shaded-netty` only contains Netty (no Gson, Jersey, etc.)
- Updating JUST Netty doesn't affect the other 6 libraries

### ✅ No Conflicts With Spark/YARN/Hadoop

```
Spark/YARN Dependencies:
├── io.netty:netty-all:4.1.115.Final
│   └── Classes at: io.netty.*

Pentaho Shaded Netty:
├── org.pentaho.hadoop:pentaho-shaded-netty
│   └── Classes at: org.apache.hadoop.hbase.thirdparty.io.netty.*

Result: ✅ NO CONFLICT - Different package namespaces
```

**Why it works:**

- Spark/YARN use `io.netty.*` (standard namespace)
- Our shaded JAR uses `org.apache.hadoop.hbase.thirdparty.io.netty.*` (relocated namespace)
- JVM sees them as **completely different classes**
- Both can coexist peacefully

### ✅ HBase Client Code Compatibility

HBase client code expects:

```java
import org.apache.hadoop.hbase.thirdparty.io.netty.buffer.ByteBuf;
import org.apache.hadoop.hbase.thirdparty.io.netty.handler.codec.smtp.*;
```

Our shaded JAR provides exactly these packages after relocation.

## Build & Usage

### Build the Module

```bash
cd third-party-libraries
mvn clean install
```

This creates:

- `pentaho-shaded-netty-<revision>.jar` (~7MB)
- Contains all Netty modules with relocated packages
- Installs to local Maven repo

### Verify the Shading

```bash
# Extract JAR
unzip -l pentaho-shaded-netty/target/pentaho-shaded-netty-*.jar | grep smtp

# Should see paths like:
org/apache/hadoop/hbase/thirdparty/io/netty/handler/codec/smtp/
```

### Where It's Used

**File**: `shims/emr770/pmr/pom.xml`

**Before**:

```xml
<dependency>
  <groupId>org.apache.hbase.thirdparty</groupId>
  <artifactId>hbase-shaded-netty</artifactId>
  <version>4.1.10</version>
</dependency>
```

**After** (version managed by `pentaho-hadoop-shims/pom.xml`):

```xml
<dependency>
  <groupId>org.pentaho.hadoop</groupId>
  <artifactId>pentaho-shaded-netty</artifactId>
</dependency>
```

## Testing Plan

1. **Build Test**:

   ```bash
   cd pentaho-hadoop-shims
   mvn clean install -DskipTests
   ```

2. **HBase Client Test**:
   - Connect to HBase cluster
   - Verify read/write operations work
   - Check no ClassNotFoundException

3. **Spark Integration Test**:
   - Run Spark job on EMR 7.7.0
   - Verify no Netty version conflicts
   - Check both Spark and HBase work simultaneously

4. **Security Scan**:

   ```bash
  # Confirm the embedded Netty version is no longer flagged.
   ```

## Maintenance

### Updating Netty Version

To upgrade to future Netty versions:

1. Edit `pentaho-shaded-netty/pom.xml`:

   ```xml
   <properties>
     <netty.version>4.1.XXX.Final</netty.version>
     <revision>${netty.version}-pentaho-N</revision>
   </properties>
   ```

2. Rebuild:

   ```bash
   mvn clean install
   ```

3. Update the centrally managed wrapper version in `pentaho-hadoop-shims` and
  rebuild the affected shims.

### Updating Other HBase ThirdParty JARs

If Apache releases new versions of the other 6 shaded JARs:

Edit `shims/emr770/pmr/pom.xml`:

```xml
<properties>
  <org.apache.hbase.thirdparty.version>4.1.XX</org.apache.hbase.thirdparty.version>
</properties>
```

**Important**: Our `pentaho-shaded-netty` is independent, so bumping `org.apache.hbase.thirdparty.version` won't affect it.

## Technical Details

### Maven Shade Plugin Configuration

The key configuration in our POM:

```xml
<relocations>
  <relocation>
    <pattern>io.netty</pattern>
    <shadedPattern>org.apache.hadoop.hbase.thirdparty.io.netty</shadedPattern>
  </relocation>
</relocations>
```

This:

- Takes all classes under `io.netty.*`
- Rewrites bytecode to relocate to `org.apache.hadoop.hbase.thirdparty.io.netty.*`
- Updates all internal references (imports, method calls, etc.)
- Produces a JAR with no trace of original `io.netty.*` packages

### JAR Manifest

The shaded JAR includes metadata:

```
Implementation-Title: Pentaho Shaded Netty
Shaded-Netty-Version: <netty-version>
```
