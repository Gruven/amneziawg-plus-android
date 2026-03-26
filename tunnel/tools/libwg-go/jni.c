/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <net/if.h>
#include <linux/if_tun.h>
#include <android/log.h>

#define LOG_TAG "AmneziaWG/JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct go_string { const char *str; long n; };
extern int awgTurnOn(struct go_string ifname, int tun_fd, struct go_string settings);
extern void awgTurnOff(int handle);
extern int awgGetSocketV4(int handle);
extern int awgGetSocketV6(int handle);
extern char *awgGetConfig(int handle);
extern char *awgVersion();

JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_awgTurnOn(JNIEnv *env, jclass c, jstring ifname, jint tun_fd, jstring settings)
{
	const char *ifname_str = (*env)->GetStringUTFChars(env, ifname, 0);
	size_t ifname_len = (*env)->GetStringUTFLength(env, ifname);
	const char *settings_str = (*env)->GetStringUTFChars(env, settings, 0);
	size_t settings_len = (*env)->GetStringUTFLength(env, settings);
	int ret = awgTurnOn((struct go_string){
		.str = ifname_str,
		.n = ifname_len
	}, tun_fd, (struct go_string){
		.str = settings_str,
		.n = settings_len
	});
	(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
	(*env)->ReleaseStringUTFChars(env, settings, settings_str);
	return ret;
}

JNIEXPORT void JNICALL Java_org_amnezia_awg_GoBackend_awgTurnOff(JNIEnv *env, jclass c, jint handle)
{
	awgTurnOff(handle);
}

JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_awgGetSocketV4(JNIEnv *env, jclass c, jint handle)
{
	return awgGetSocketV4(handle);
}

JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_awgGetSocketV6(JNIEnv *env, jclass c, jint handle)
{
	return awgGetSocketV6(handle);
}

JNIEXPORT jstring JNICALL Java_org_amnezia_awg_GoBackend_awgGetConfig(JNIEnv *env, jclass c, jint handle)
{
	jstring ret;
	char *config = awgGetConfig(handle);
	if (!config)
		return NULL;
	ret = (*env)->NewStringUTF(env, config);
	free(config);
	return ret;
}

JNIEXPORT jstring JNICALL Java_org_amnezia_awg_GoBackend_awgVersion(JNIEnv *env, jclass c)
{
	jstring ret;
	char *version = awgVersion();
	if (!version)
		return NULL;
	ret = (*env)->NewStringUTF(env, version);
	free(version);
	return ret;
}

JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_openTun(JNIEnv *env, jclass c, jstring ifname)
{
	int fd = open("/dev/net/tun", O_RDWR);
	if (fd < 0) {
		LOGE("open(/dev/net/tun) failed: %s (errno=%d)", strerror(errno), errno);
		fd = open("/dev/tun", O_RDWR);
	}
	if (fd < 0) {
		LOGE("open(/dev/tun) failed: %s (errno=%d)", strerror(errno), errno);
		return -1;
	}

	LOGE("TUN device opened successfully, fd=%d", fd);

	struct ifreq ifr;
	memset(&ifr, 0, sizeof(ifr));
	ifr.ifr_flags = IFF_TUN | IFF_NO_PI;

	const char *ifname_str = (*env)->GetStringUTFChars(env, ifname, 0);
	strncpy(ifr.ifr_name, ifname_str, IFNAMSIZ - 1);
	(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);

	if (ioctl(fd, TUNSETIFF, &ifr) < 0) {
		LOGE("ioctl(TUNSETIFF, %s) failed: %s (errno=%d)", ifr.ifr_name, strerror(errno), errno);
		close(fd);
		return -2;
	}

	LOGE("TUN interface %s attached, fd=%d", ifr.ifr_name, fd);
	return fd;
}

JNIEXPORT void JNICALL Java_org_amnezia_awg_GoBackend_closeTun(JNIEnv *env, jclass c, jint fd)
{
	if (fd >= 0)
		close(fd);
}

JNIEXPORT jint JNICALL Java_org_amnezia_awg_GoBackend_receiveTunFd(JNIEnv *env, jclass c, jstring socketPath)
{
	const char *path = (*env)->GetStringUTFChars(env, socketPath, 0);

	int server = socket(AF_UNIX, SOCK_STREAM, 0);
	if (server < 0) {
		LOGE("receiveTunFd: socket() failed: %s", strerror(errno));
		(*env)->ReleaseStringUTFChars(env, socketPath, path);
		return -1;
	}

	struct sockaddr_un addr;
	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
	unlink(path);

	if (bind(server, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		LOGE("receiveTunFd: bind(%s) failed: %s", path, strerror(errno));
		close(server);
		(*env)->ReleaseStringUTFChars(env, socketPath, path);
		return -2;
	}

	/* Разрешаем root-процессу подключаться */
	chmod(path, 0777);

	if (listen(server, 1) < 0) {
		LOGE("receiveTunFd: listen() failed: %s", strerror(errno));
		close(server);
		unlink(path);
		(*env)->ReleaseStringUTFChars(env, socketPath, path);
		return -3;
	}

	(*env)->ReleaseStringUTFChars(env, socketPath, path);

	int client = accept(server, NULL, NULL);
	close(server);
	if (client < 0) {
		LOGE("receiveTunFd: accept() failed: %s", strerror(errno));
		return -4;
	}

	/* Получаем fd через SCM_RIGHTS */
	char buf[1];
	struct iovec iov = { .iov_base = buf, .iov_len = 1 };

	union {
		char buf[CMSG_SPACE(sizeof(int))];
		struct cmsghdr align;
	} cmsg_buf;

	struct msghdr msg;
	memset(&msg, 0, sizeof(msg));
	msg.msg_iov = &iov;
	msg.msg_iovlen = 1;
	msg.msg_control = cmsg_buf.buf;
	msg.msg_controllen = sizeof(cmsg_buf.buf);

	if (recvmsg(client, &msg, 0) < 0) {
		LOGE("receiveTunFd: recvmsg() failed: %s", strerror(errno));
		close(client);
		return -5;
	}
	close(client);

	struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
	if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) {
		LOGE("receiveTunFd: no SCM_RIGHTS in message");
		return -6;
	}

	int tun_fd;
	memcpy(&tun_fd, CMSG_DATA(cmsg), sizeof(int));
	LOGE("receiveTunFd: received tun fd=%d", tun_fd);
	return tun_fd;
}
