# Download and expand the Amazon Corretto 11 JDK, then use it to build the embedded JRE for inside
# the Mac application. But if it already exists (because we use a cache action to speed things up),
# we can skip this section.
if [ ! -d Runtime ]; then
    curl --location https://corretto.aws/downloads/latest/amazon-corretto-11-x64-macos-jdk.tar.gz \
         --output runtime.tar.gz
    tar xvf runtime.tar.gz
    amazon-corretto-11.jdk/Contents/Home/bin/jlink --no-header-files --no-man-pages --compress=2 --strip-debug \
        --add-modules=java.base,java.desktop,java.management,java.naming,java.prefs,java.sql,jdk.zipfs,jdk.unsupported \
        --output Runtime
fi

# Move the downloaded cross-platform executable Jar into an Input folder to be used in building the
# native app bundle.
mkdir Input
mv beat-link-trigger.jar Input

# See if the secrets needed to code-sign the native application are present.
if  [ "$IDENTITY_PASSPHRASE" != "" ]; then

    # We have secrets! Set up a keychain to hold the signing certificate. We can use the same
    # secret passphrase that we will use to import it as the keychain password, for simplicity.
    security create-keychain -p "$IDENTITY_PASSPHRASE" build.keychain
    security default-keychain -s build.keychain
    security unlock-keychain -p "$IDENTITY_PASSPHRASE" build.keychain

    # Put the base-64 encoded signing certificicate into a text file, decode it to binary form.
    echo "$IDENTITY_P12_B64" > DS_ID_App.p12.txt
    openssl base64 -d -in DS_ID_App.p12.txt -out DS_ID_App.p12

    # Install the decoded signing certificate into our unlocked build keychain.
    security import DS_ID_App.p12 -A -P "$IDENTITY_PASSPHRASE"

    # Set the keychain to allow use of the certificate without user interaction (we are headless!)
    security set-key-partition-list -S apple-tool:,apple: -s -k "$IDENTITY_PASSPHRASE" build.keychain

    # Finally, run jpackage to build the native application as a code-signed disk image.
    jpackage --name $blt_name --input Input --runtime-image Runtime \
             --icon .github/resources/BeatLink.icns --main-jar beat-link-trigger.jar \
             --description $blt_description --copyright $blt_copyright --vendor $blt_vendor \
             --type dmg --mac-package-identifier "org.deepsymmetry.beat-link-trigger" \
             --mac-sign --mac-signing-key-user-name $blt_mac_signing_name \
             --app-version $version_tag
else
    # We have no secrets, so build the native application disk image without code signing.
    jpackage --name $blt_name --input Input --runtime-image Runtime \
             --icon .github/resources/BeatLink.icns --main-jar beat-link-trigger.jar \
             --description $blt_description --copyright $blt_copyright --vendor $blt_vendor \
             --type dmg --mac-package-identifier "org.deepsymmetry.beat-link-trigger" \
             --app-version $version_tag
fi

# Rename the disk image to the name we like to use for the release artifact.
mv "$dmg_name" "$artifact_name"
