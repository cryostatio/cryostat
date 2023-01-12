```bash
$ kind create cluster # or otherwise get access to a k8s cluster
$ kompose convert -f ../container-compose.yml
$ kubectl apply -f . # possibly edit deployments to edit image tags first
$ kubectl port-forward svc/cryostat 8181 # in a new terminal
$ kubectl port-forward svc/minio 9001 # in a new terminal
$ kubectl multiforward smoketest # if you have the multiforward plugin installed, you can do this instead of the manual port-forwards above
```
