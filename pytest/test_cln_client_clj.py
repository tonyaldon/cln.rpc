from pyln.testing.fixtures import *
import json
import os
from pathlib import Path

def test_call(node_factory):
    node = node_factory.get_node()
    node_info = node.rpc.getinfo()
    socket_file = (Path(node_info["lightning-dir"]) / "lightning-rpc").as_posix()

    # call to getinfo
    getinfo_cmd = f"clojure -X call/getinfo :socket-file '\"{socket_file}\"'"
    os.chdir("pytest")
    getinfo_str = os.popen(getinfo_cmd).read()
    assert json.loads(getinfo_str) == node_info
