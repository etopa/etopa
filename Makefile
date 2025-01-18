TARGET_OUTPUT_DIR?=${PWD}/target/build
EXTRA_DIR?=extra
RUST_BUILDER?=cargo
NOTICE_FILE?=NOTICE.txt

# versions
BUILDTOOLS_VERSION=$(shell ./build-config BUILDTOOLS_VERSION)
NDK_VERSION=$(shell ./build-config NDK_VERSION)
BUNDLETOOL_VERSION=$(shell ./build-config BUNDLETOOL_VERSION)
MINIFY_VERSION=$(shell ./build-config MINIFY_VERSION)

# api
API_FILE_NAME?=etopa
API_TARGET_TRIPLE?=x86_64-unknown-linux-musl
API_STRIP?=strip
CARGO_LICENSE?=cargo-license
CARGO_UPGRADE?=cargo upgrade
NATIVE_TARGET_CPU?=native

# android
NDK_TOOLCHAIN_BIN?=$(ANDROID_NDK_ROOT)/${NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64/bin
ANDROID_BT_PATH?=$(ANDROID_HOME)/build-tools/${BUILDTOOLS_VERSION}
JNI_LIBS_PATH?=etopan-app/app/src/main/jniLibs
BUNDLETOOL_JAR?=$(ANDROID_HOME)/bundletool-${BUNDLETOOL_VERSION}.jar
ANDROID_APK_FILE?=etopa.apk
ANDROID_AAB_FILE?=${EXTRA_DIR}/etopa.aab
ANDROID_MAPPING?=${EXTRA_DIR}/mapping.txt
ANDROID_DEBUG_SYMBOLS?=${EXTRA_DIR}/native-debug-symbols.zip
ANDROID_KEYSTORE?=~/.etopa.jks
JKS_PASSWORD=$(shell cat ~/.etopa.jks.pass)
JKS_ALIAS?=etopa
JAVA_EXEC?=java
JARSIGNER_EXEC?=jarsigner
APKSIGNER_EXEC=${ANDROID_BT_PATH}/apksigner
ZIPALIGN_EXEC=${ANDROID_BT_PATH}/zipalign
DEBUG_ANDROID_KEYSTORE?=$(ANDROID_HOME)/debug.keystore
DEBUG_JKS_PASSWORD=android
DEBUG_JKS_ALIAS?=androiddebugkey

# web
WEB_FILE_NAME?=etopa.tar.xz
WASM_PACK_EXEC?=wasm-pack
GOMINIFY_EXEC?=minify-${MINIFY_VERSION}
TEMP_EWM?=/tmp/etopa_ewm

# docker
DOCKER_TAG=ltheinrich/etopa:latest

.PHONY: build clean noclean upgrade check api web android

build: clean noclean

noclean: upgrade check api web android
	\cp ${NOTICE_FILE} ${TARGET_OUTPUT_DIR}/NOTICE.txt

api:
	mkdir -p ${TARGET_OUTPUT_DIR} && rm -f ${TARGET_OUTPUT_DIR}/${API_FILE_NAME}
	${RUST_BUILDER} build -p etopai --release --target ${API_TARGET_TRIPLE} -v
	${API_STRIP} target/${API_TARGET_TRIPLE}/release/etopai
	cp target/${API_TARGET_TRIPLE}/release/etopai ${TARGET_OUTPUT_DIR}/${API_FILE_NAME}

web:
	mkdir -p ${TARGET_OUTPUT_DIR}
	rm -f ${TARGET_OUTPUT_DIR}/${WEB_FILE_NAME} && rm -rf ${TEMP_EWM}
	${WASM_PACK_EXEC} build --release --no-typescript -t web -d ../etopaw-app/pkg etopaw
	mkdir -p ${TEMP_EWM}
	${GOMINIFY_EXEC} -r -o ${TEMP_EWM}/ etopaw-app/
	cp -r -n etopaw-app ${TEMP_EWM}
	\cp etopaw-app/config.js ${TEMP_EWM}/etopaw-app/config.js
	\cp etopaw-app/js/vue.min.js ${TEMP_EWM}/etopaw-app/js/vue.min.js
	\cp etopaw-app/css/bootstrap.min.css ${TEMP_EWM}/etopaw-app/css/bootstrap.min.css
	cp ${NOTICE_FILE} ${TEMP_EWM}/etopaw-app/NOTICE.txt
	(cd ${TEMP_EWM}/etopaw-app && tar cfJ ${TARGET_OUTPUT_DIR}/etopa.tar.xz *)
	rm -rf ${TEMP_EWM}

#android-build: export RUSTFLAGS = -Clink-arg=-Wl,--hash-style=both
android-build: export CC_aarch64-linux-android = ${NDK_TOOLCHAIN_BIN}/aarch64-linux-android24-clang
android-build: export CC_armv7-linux-androideabi = ${NDK_TOOLCHAIN_BIN}/armv7a-linux-androideabi24-clang
android-build: export CC_x86_64-linux-android = ${NDK_TOOLCHAIN_BIN}/x86_64-linux-android24-clang
android-build: export CC_i686-linux-android = ${NDK_TOOLCHAIN_BIN}/i686-linux-android24-clang
android-build: export TARGET_AR = ${NDK_TOOLCHAIN_BIN}/llvm-ar
android-build:
	export PATH=$(PATH):${NDK_TOOLCHAIN_BIN}
	mkdir -p ${TARGET_OUTPUT_DIR} && mkdir -p ${TARGET_OUTPUT_DIR}/${EXTRA_DIR}
	rm -f ${TARGET_OUTPUT_DIR}/${ANDROID_AAB_FILE} && rm -f ${TARGET_OUTPUT_DIR}/${ANDROID_APK_FILE}
	export TARGET_CC=CC_aarch64-linux-android
	${RUST_BUILDER} rustc -p etopan --release --target aarch64-linux-android -v -- -C linker=$(CC_aarch64-linux-android)
	export TARGET_CC=CC_armv7-linux-androideabi
	${RUST_BUILDER} rustc -p etopan --release --target armv7-linux-androideabi -v -- -C linker=$(CC_armv7-linux-androideabi)
	export TARGET_CC=CC_x86_64-linux-android 
	${RUST_BUILDER} rustc -p etopan --release --target x86_64-linux-android -v -- -C linker=$(CC_x86_64-linux-android)
	export TARGET_CC=CC_i686-linux-android
	${RUST_BUILDER} rustc -p etopan --release --target i686-linux-android -v -- -C linker=$(CC_i686-linux-android)
	rm -rf ${JNI_LIBS_PATH} && mkdir -p ${JNI_LIBS_PATH}/arm64-v8a && mkdir -p ${JNI_LIBS_PATH}/armeabi-v7a \
	  && mkdir -p ${JNI_LIBS_PATH}/x86_64 && mkdir -p ${JNI_LIBS_PATH}/x86
	cp target/aarch64-linux-android/release/libetopan.so ${JNI_LIBS_PATH}/arm64-v8a/libetopan.so
	cp target/armv7-linux-androideabi/release/libetopan.so ${JNI_LIBS_PATH}/armeabi-v7a/libetopan.so
	cp target/i686-linux-android/release/libetopan.so ${JNI_LIBS_PATH}/x86/libetopan.so
	cp target/x86_64-linux-android/release/libetopan.so ${JNI_LIBS_PATH}/x86_64/libetopan.so
	mkdir -p etopan-app/app/src/main/assets && \cp ${NOTICE_FILE} etopan-app/app/src/main/assets/NOTICE.txt
	(cd etopan-app && ./gradlew clean && ./gradlew :app:bundleRelease && ./gradlew assembleRelease && ./gradlew --stop)

android: android-build
ifndef DEBUG_SIGN
	${APKSIGNER_EXEC} sign --v4-signing-enabled false --v3-signing-enabled true --ks ${ANDROID_KEYSTORE} \
	  --ks-key-alias ${JKS_ALIAS} --ks-pass pass:${JKS_PASSWORD} --out ${TARGET_OUTPUT_DIR}/${ANDROID_APK_FILE} \
	  etopan-app/app/build/outputs/apk/release/app-release-unsigned.apk
	${JARSIGNER_EXEC} -keystore ${ANDROID_KEYSTORE} -storepass ${JKS_PASSWORD} -sigalg SHA256withRSA \
	  -digest-alg SHA-256 etopan-app/app/build/outputs/bundle/release/app-release.aab etopa
	cp etopan-app/app/build/outputs/bundle/release/app-release.aab ${TARGET_OUTPUT_DIR}/${ANDROID_AAB_FILE}
	cp etopan-app/app/build/outputs/mapping/release/mapping.txt ${TARGET_OUTPUT_DIR}/${ANDROID_MAPPING}
	cp etopan-app/app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip ${TARGET_OUTPUT_DIR}/${ANDROID_DEBUG_SYMBOLS}
else
	${APKSIGNER_EXEC} sign --v4-signing-enabled false --v3-signing-enabled false --v2-signing-enabled true --ks ${DEBUG_ANDROID_KEYSTORE} \
	  --ks-key-alias ${DEBUG_JKS_ALIAS} --ks-pass pass:${DEBUG_JKS_PASSWORD} --out ${TARGET_OUTPUT_DIR}/${ANDROID_APK_FILE} \
	  etopan-app/app/build/outputs/apk/release/app-release-unsigned.apk
endif

docker-only:
	sudo chown -R -f $(shell whoami):$(shell whoami) docker/etopa || :
	docker build -t ${DOCKER_TAG} .

upgrade: export CARGO_REGISTRIES_CRATES_IO_PROTOCOL = git
upgrade:
	$(eval BUNDLETOOL_VERSION := $(shell curl --silent "https://api.github.com/repos/google/bundletool/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/'))
	sed -i '/export BUNDLETOOL_VERSION=*/c\export BUNDLETOOL_VERSION=$(BUNDLETOOL_VERSION)' build-config
	$(eval MINIFY_VERSION := $(shell curl --silent "https://api.github.com/repos/tdewolff/minify/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/'))
	sed -i '/export MINIFY_VERSION=*/c\export MINIFY_VERSION=$(MINIFY_VERSION)' build-config
	$(eval NDK_VERSION := $(shell curl --silent "https://api.github.com/repos/android/ndk/releases/latest" | grep '"body":' | grep -o 'ndkVersion \\"[0-9]*\.[0-9]*\.[0-9]*\\"' | grep -o '[0-9]*\.[0-9]*\.[0-9]*'))
	sed -i '/ndkVersion "*"/c\    ndkVersion "$(NDK_VERSION)"' etopan-app/app/build.gradle
	${RUST_BUILDER} fetch
	${RUST_BUILDER} update
	${CARGO_UPGRADE} --incompatible
	head -841 ${NOTICE_FILE} > ${NOTICE_FILE}.tmp && mv ${NOTICE_FILE}.tmp ${NOTICE_FILE}
	${CARGO_LICENSE} -t | sed "s/ring\t\tLICENSE/ring\t\tring's license/g" | sed "s/webpki\t\tLICENSE/ring\t\tISC AND BSD-3-Clause/g" >> ${NOTICE_FILE}
	mkdir -p etopan-app/app/src/main/assets && \cp ${NOTICE_FILE} etopan-app/app/src/main/assets/NOTICE.txt

clean:
	${RUST_BUILDER} clean
	(cd etopan-app && ./gradlew clean)

check:
	${RUST_BUILDER} fmt --all --verbose -- --check
	${RUST_BUILDER} clippy --workspace --all-features --all-targets --verbose -- -D warnings
	${RUST_BUILDER} test -p etopa -p etopai -p etopan --all-features --all-targets --verbose
	${WASM_PACK_EXEC} test --node etopaw
