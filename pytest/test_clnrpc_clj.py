from pyln.testing.fixtures import *
from ephemeral_port_reserve import reserve
import json
import os

os.chdir("pytest")

def test_call(node_factory):
    l1, l2 = node_factory.line_graph(2)
    l1_info = l1.rpc.getinfo()
    l1_socket_file = os.path.join(l1_info["lightning-dir"], "lightning-rpc")
    l2_info = l2.rpc.getinfo()
    l2_socket_file = os.path.join(l2_info["lightning-dir"], "lightning-rpc")

    # call to getinfo
    # 1) default case
    getinfo_cmd = f"clojure -X rpc/call-getinfo :socket-file '\"{l1_socket_file}\"'"
    getinfo_str = os.popen(getinfo_cmd).read()
    assert json.loads(getinfo_str) == l1_info
    # 2) test payload
    getinfo_cmd = f"clojure -X rpc/call-getinfo :socket-file '\"{l1_socket_file}\"' :test-payload true"
    getinfo_str = os.popen(getinfo_cmd).read()
    assert json.loads(getinfo_str) == l1_info

    # Check the jsonrpc id used in the getinfo request to lightningd
    # 1) default prefix: clnrpc-clj
    jsonrpc_id_cmd = f"clojure -X rpc/jsonrpc-id :socket-file '\"{l1_socket_file}\"'"
    jsonrpc_id_str = os.popen(jsonrpc_id_cmd).read()
    print(jsonrpc_id_str)
    assert re.search(r"^clnrpc-clj:getinfo#[0-9]+$", jsonrpc_id_str)
    # 2) custom prefix: my-prefix
    jsonrpc_id_cmd = f"clojure -X rpc/jsonrpc-id :socket-file '\"{l1_socket_file}\"' :json-id-prefix '\"my-prefix\"'"
    jsonrpc_id_str = os.popen(jsonrpc_id_cmd).read()
    print(jsonrpc_id_str)
    assert re.search(r"^my-prefix:getinfo#[0-9]+$", jsonrpc_id_str)

    # call methods with payload: invoice and pay
    # l2 creates an bolt11 invoice
    l2_invoice_cmd = f"clojure -X rpc/call-invoice :socket-file '\"{l2_socket_file}\"'"
    l2_bolt11 = os.popen(l2_invoice_cmd).read()
    # l1 pay the bolt11 invoice
    l1_pay_cmd = f"clojure -X rpc/call-pay :socket-file '\"{l1_socket_file}\"' :bolt11 '\"{l2_bolt11}\"'"
    l1_pay_status = os.popen(l1_pay_cmd).read()
    assert l1_pay_status == "complete"

def test_unix_socket_path_too_long(node_factory, bitcoind, directory, executor, db_provider):
    lightning_dir = os.path.join(directory, "path-too-long-" * 15)
    os.makedirs(lightning_dir)
    db = db_provider.get_db(lightning_dir, "test_unix_socket_path_too_long", 1)
    db.provider = db_provider
    l1 = LightningNode(1, lightning_dir, bitcoind, executor, False, db=db, port=reserve())
    l1.start()

    socket_file = os.path.join(lightning_dir, "regtest", "lightning-rpc")

    # call to getinfo
    getinfo_cmd = f"clojure -X rpc/call-getinfo :socket-file '\"{socket_file}\"'"
    getinfo_str = os.popen(getinfo_cmd).read()
    assert json.loads(getinfo_str) == l1.rpc.getinfo()
    l1.stop()
