# openbu-relay
Openbu-relay is a [Helm](https://github.com/helm/helm) chart for [Kubernetes](https://github.com/kubernetes/kubernetes) based on [go-gost/gost](https://github.com/go-gost/gost). [go-gost/gost](https://github.com/go-gost/gost) is a [SOCKS5](https://en.wikipedia.org/wiki/SOCKS) proxy written in [Golang](https://github.com/golang/go).

# Why
## Why SOCKS5
It allows configure just for access to your printer, it's internal(MJPEG or [RTSP](https://en.wikipedia.org/wiki/Real-Time_Streaming_Protocol)) camera, and external [RTSP](https://en.wikipedia.org/wiki/Real-Time_Streaming_Protocol) streams on your private network without a VPN that would interfere with the networking on the rest of your [Android](https://www.android.com/) device. Many people already have VPNs into the location where their printer is located, and those can be used instead. This is just an alternative.

## Why Kubernetes
So much software now comes as [Docker](https://github.com/docker) images. It is common to use [Docker Compose](https://github.com/docker/compose), but it is an incomplete solution. [Kubernetes](https://github.com/kubernetes/kubernetes) has so many tools to make things more automated.

1. [cert-manager](https://github.com/cert-manager/cert-manager), LetsEncrypt SSL certificate management
2. [external-dns](https://github.com/kubernetes-sigs/external-dns), external(public) DNS management
3. [traefik](https://github.com/traefik/traefik), load balancer management
4. [metallb](https://github.com/metallb/metallb), exposing load balancer ip addresses to the private network
5. [load-path-provisioner](https://github.com/rancher/local-path-provisioner), simple persistent storage management
6. [openebs](https://github.com/openebs/openebs), advanced persistent storage management

# SOCKS5
## DNS
Note, SOCKS5 doesn't take over your phone's DNS. Yet it can still let you use internal DNS names. The main example would be an external [RTSP](https://en.wikipedia.org/wiki/Real-Time_Streaming_Protocol) stream. In my external [RTSP](https://en.wikipedia.org/wiki/Real-Time_Streaming_Protocol)'s URL is `rtsp://electron.cygnusx-1.org:8544/cam`. Yet `electron.cygnusx-1.org` is only resolvable internally. [SOCKS5](https://en.wikipedia.org/wiki/SOCKS) manages this by using the proxy service to resolve the ip address.

# Kubernetes
I personally use [Kubernetes](https://github.com/kubernetes/kubernetes) using packages from [Fedora Linux](https://fedoraproject.org/) via [systemd](https://github.com/systemd/systemd) and following [Kubernetes the Hard Way](https://github.com/kelseyhightower/kubernetes-the-hard-way). I do this, because I set this up years ago. For newcomers I recommend [k3s](https://github.com/k3s-io/k3s/).

# Networking explanation
## My setup
[ Internet ] -> [Public IP address] -> [Linux firewall] -> [Port forwarding via iptables for port 1080] -> [MetalLB private ip] -> [openbu-relay LoadBalancer service] -> [openbu-relay pod]

In your case your firewall could be your personal router, wifi AP router, ISP supplied router, etc.

## Public IP address
My public ip address currently is `68.3.136.57`, and `cygnusx-1.org` points to it. You can find your own public ip address with [MyIPAddress](https://www.myipaddress.com/).

## Examples
### Kubernetes service
```
kubectl get services openbu-relay
NAME           TYPE           CLUSTER-IP    EXTERNAL-IP     PORT(S)          AGE
openbu-relay   LoadBalancer   10.32.69.24   192.168.1.12    1080:11357/TCP   24h
```

`192.168.1.12` is actually a private ip address managed via [metallb](https://github.com/metallb/metallb). By using an [metallb](https://github.com/metallb/metallb) managed private ip address it avoids port conflicts with software directly running on the host running [Kubernetes](https://github.com/kubernetes/kubernetes). I do run some software outside [Kubernetes](https://github.com/kubernetes/kubernetes) like [Samba](https://en.wikipedia.org/wiki/Samba_(software)) and [Nexus](https://github.com/sonatype/nexus-public).

### Kubernetes pod
```
kubectl get pod | grep openbu-relay
default        openbu-relay-cfc46fad8-10rga               1/1     Running   1 (14h ago)     24h
```

# Settings for Openbu's Remote Relay

1. Relay hostname, public DNS hostname pointing to your public IP address, like `cygnusx-1.org`
2. Relay port, should be `1080` unless you change it
3. Relay username, whatever you use as the username with the [Helm](https://github.com/helm/helm) chart below
4. Relay password, whatever you use as the password with the [Helm](https://github.com/helm/helm) chart below

# Helm
## Example helm commands
### Non-literal
```
cd openbu/helm
helm upgrade --install openbu-relay ./openbu-relay --set auth.username=username --set auth.password=password --set tls.certificateSecretName=secret-name
```

### Literal
```
cd openbu/helm
helm upgrade --install openbu-relay ./openbu-relay --set auth.username=ngrennan --set auth.password=ieufimfole90g2um6mszlmplolrom1w9 --set tls.certificateSecretName=storage-tls
```

### SSL certificate managed by cert-manager
I personally use a wildcard cerificate for my domain, `cygnusx-1.org`. It works for both `cygnusx-1.org` and `*.cygnusx-1.org`.

You want to use the `SECRET` not the `NAME`. Hence why the option for the [Helm](https://github.com/helm/helm) is `tls.certificateSecretName` not `tls.certificateName`.

#### Example
```
kubectl get certificate
NAME      READY   SECRET        AGE
storage   True    storage-tls   3y285d
```
