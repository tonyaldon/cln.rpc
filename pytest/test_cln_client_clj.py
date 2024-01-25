from pyln.testing.fixtures import *
import os
from pathlib import Path

def test_call(node_factory):
    node = node_factory.get_node()
    node_info = node.rpc.getinfo()
    socket_file = (Path(node_info["lightning-dir"]) / "lightning-rpc").as_posix()
    node_id = node_info["id"]
    getinfo_cmd = f"clojure -X call/getinfo :socket-file '\"{socket_file}\"'"
    os.chdir("pytest")
    assert os.popen(getinfo_cmd).read() == node_id
