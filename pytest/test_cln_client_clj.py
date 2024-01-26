from pyln.testing.fixtures import *
import json
import os
from pathlib import Path

def test_call(node_factory):
    node = node_factory.get_node()
    node_info = node.rpc.getinfo()
    socket_file = (Path(node_info["lightning-dir"]) / "lightning-rpc").as_posix()

    os.chdir("pytest")

    # call to getinfo
    # 1) default case
    getinfo_cmd = f"clojure -X call/getinfo :socket-file '\"{socket_file}\"'"
    getinfo_str = os.popen(getinfo_cmd).read()
    assert json.loads(getinfo_str) == node_info
    # 2) test payload
    getinfo_cmd = f"clojure -X call/getinfo :socket-file '\"{socket_file}\"' :test-payload true"
    getinfo_str = os.popen(getinfo_cmd).read()
    assert json.loads(getinfo_str) == node_info

    # Check the jsonrpc id used in the getinfo request to lightningd
    # 1) default prefix: cln-client-clj
    jsonrpc_id_cmd = f"clojure -X call/jsonrpc-id :socket-file '\"{socket_file}\"'"
    jsonrpc_id_str = os.popen(jsonrpc_id_cmd).read()
    print(jsonrpc_id_str)
    assert re.search(r"^cln-client-clj:getinfo#[0-9]+$", jsonrpc_id_str)
    # 2) custom prefix: my-prefix
    jsonrpc_id_cmd = f"clojure -X call/jsonrpc-id :socket-file '\"{socket_file}\"' :json-id-prefix '\"my-prefix\"'"
    jsonrpc_id_str = os.popen(jsonrpc_id_cmd).read()
    print(jsonrpc_id_str)
    assert re.search(r"^my-prefix:getinfo#[0-9]+$", jsonrpc_id_str)
