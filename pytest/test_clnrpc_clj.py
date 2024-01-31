from pyln.testing.fixtures import *
from ephemeral_port_reserve import reserve
import json
import os

os.chdir("pytest")

def test_call(node_factory):
    node = node_factory.get_node()
    node_info = node.rpc.getinfo()
    socket_file = os.path.join(node_info["lightning-dir"], "lightning-rpc")

    # call to getinfo
    # 1) default case
    getinfo_cmd = f"clojure -X rpc/getinfo :socket-file '\"{socket_file}\"'"
    getinfo_str = os.popen(getinfo_cmd).read()
    assert json.loads(getinfo_str) == node_info
    # 2) test payload
    getinfo_cmd = f"clojure -X rpc/getinfo :socket-file '\"{socket_file}\"' :test-payload true"
    getinfo_str = os.popen(getinfo_cmd).read()
    assert json.loads(getinfo_str) == node_info

    # Check the jsonrpc id used in the getinfo request to lightningd
    # 1) default prefix: clnrpc-clj
    jsonrpc_id_cmd = f"clojure -X rpc/jsonrpc-id :socket-file '\"{socket_file}\"'"
    jsonrpc_id_str = os.popen(jsonrpc_id_cmd).read()
    print(jsonrpc_id_str)
    assert re.search(r"^clnrpc-clj:getinfo#[0-9]+$", jsonrpc_id_str)
    # 2) custom prefix: my-prefix
    jsonrpc_id_cmd = f"clojure -X rpc/jsonrpc-id :socket-file '\"{socket_file}\"' :json-id-prefix '\"my-prefix\"'"
    jsonrpc_id_str = os.popen(jsonrpc_id_cmd).read()
    print(jsonrpc_id_str)
    assert re.search(r"^my-prefix:getinfo#[0-9]+$", jsonrpc_id_str)

def test_unix_socket_path_too_long(node_factory, bitcoind, directory, executor, db_provider):
    lightning_dir = os.path.join(directory, "path-too-long-" * 15)
    os.makedirs(lightning_dir)
    db = db_provider.get_db(lightning_dir, "test_unix_socket_path_too_long", 1)
    db.provider = db_provider
    node = LightningNode(1, lightning_dir, bitcoind, executor, False, db=db, port=reserve())
    node.start()

    socket_file = os.path.join(lightning_dir, "regtest", "lightning-rpc")

    # call to getinfo
    getinfo_cmd = f"clojure -X rpc/getinfo :socket-file '\"{socket_file}\"'"
    getinfo_str = os.popen(getinfo_cmd).read()
    assert json.loads(getinfo_str) == node.rpc.getinfo()
    node.stop()
