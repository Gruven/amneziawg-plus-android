/* SPDX-License-Identifier: Apache-2.0
 *
 * Helper binary executed as root.
 * Opens /dev/tun, creates TUN interface via ioctl(TUNSETIFF),
 * sends fd to the app via Unix domain socket (SCM_RIGHTS).
 *
 * Usage: tun-creator <ifname> <socket_path>
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <net/if.h>
#include <linux/if_tun.h>

static int open_tun(const char *ifname)
{
    int fd = open("/dev/net/tun", O_RDWR);
    if (fd < 0)
        fd = open("/dev/tun", O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "open tun: %s\n", strerror(errno));
        return -1;
    }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);

    if (ioctl(fd, TUNSETIFF, &ifr) < 0) {
        fprintf(stderr, "ioctl TUNSETIFF: %s\n", strerror(errno));
        close(fd);
        return -1;
    }

    return fd;
}

static int send_fd(int sock, int fd)
{
    char buf[1] = {0};
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

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));

    if (sendmsg(sock, &msg, 0) < 0) {
        fprintf(stderr, "sendmsg: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

int main(int argc, char *argv[])
{
    if (argc != 3) {
        fprintf(stderr, "Usage: %s <ifname> <socket_path>\n", argv[0]);
        return 1;
    }

    const char *ifname = argv[1];
    const char *socket_path = argv[2];

    int tun_fd = open_tun(ifname);
    if (tun_fd < 0)
        return 1;

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        fprintf(stderr, "socket: %s\n", strerror(errno));
        close(tun_fd);
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    /* Retry connecting (server may not be ready yet) */
    int connected = 0;
    for (int i = 0; i < 50; i++) {
        if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
            connected = 1;
            break;
        }
        usleep(100000); /* 100ms */
    }

    if (!connected) {
        fprintf(stderr, "connect %s: %s\n", socket_path, strerror(errno));
        close(sock);
        close(tun_fd);
        return 1;
    }

    int ret = send_fd(sock, tun_fd);
    close(sock);
    close(tun_fd);
    return ret < 0 ? 1 : 0;
}
