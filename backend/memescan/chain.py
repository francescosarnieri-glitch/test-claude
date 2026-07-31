"""Client JSON-RPC per Robinhood Chain (EVM, chain id 4663).

Volutamente senza web3.py: servono poche chiamate (eth_call, eth_getLogs,
eth_blockNumber) e con il JSON-RPC grezzo si possono raggruppare in batch,
che e' l'unico modo per restare dentro i rate limit di un RPC gratuito.
"""

from __future__ import annotations

import asyncio
from typing import Any, Sequence

from eth_abi import decode as abi_decode
from eth_abi import encode as abi_encode
from eth_utils import keccak, to_checksum_address

from .config import settings
from .util import HttpClient, get_logger

log = get_logger("memescan.chain")

ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
DEAD_ADDRESS = "0x000000000000000000000000000000000000dead"
BURN_ADDRESSES = {ZERO_ADDRESS, DEAD_ADDRESS, "0x0000000000000000000000000000000000000001"}


def selector(signature: str) -> str:
    """Primi 4 byte del keccak della firma: il selettore della funzione."""
    return "0x" + keccak(text=signature)[:4].hex()


def event_topic(signature: str) -> str:
    return "0x" + keccak(text=signature).hex()


# --- Selettori usati di frequente -------------------------------------------
SEL = {
    "name": selector("name()"),
    "symbol": selector("symbol()"),
    "decimals": selector("decimals()"),
    "totalSupply": selector("totalSupply()"),
    "balanceOf": selector("balanceOf(address)"),
    "owner": selector("owner()"),
    "getOwner": selector("getOwner()"),
    "factory": selector("factory()"),
    "token0": selector("token0()"),
    "token1": selector("token1()"),
    "getReserves": selector("getReserves()"),
    "liquidity": selector("liquidity()"),
    "fee": selector("fee()"),
}

# --- Topic degli eventi ------------------------------------------------------
TOPIC_TRANSFER = event_topic("Transfer(address,address,uint256)")
TOPIC_PAIR_CREATED = event_topic("PairCreated(address,address,address,uint256)")
TOPIC_POOL_CREATED = event_topic("PoolCreated(address,address,uint24,int24,address)")
TOPIC_OWNERSHIP_TRANSFERRED = event_topic("OwnershipTransferred(address,address)")

# Firme "pericolose": se il bytecode contiene questi selettori il contratto puo'
# ancora coniare token, bloccare un venditore o alzare la tassa a piacimento.
DANGEROUS_SELECTORS: dict[str, str] = {
    selector("mint(address,uint256)"): "mint",
    selector("mint(uint256)"): "mint",
    selector("setBlacklist(address,bool)"): "blacklist",
    selector("blacklist(address)"): "blacklist",
    selector("addBlacklist(address)"): "blacklist",
    selector("setBots(address[],bool)"): "blacklist",
    selector("setFees(uint256,uint256)"): "fee_mutabile",
    selector("setFee(uint256)"): "fee_mutabile",
    selector("setTaxes(uint256,uint256,uint256)"): "fee_mutabile",
    selector("pause()"): "pausable",
    selector("setTradingEnabled(bool)"): "trading_switch",
    selector("enableTrading()"): "trading_switch",
    selector("setMaxTxAmount(uint256)"): "max_tx_mutabile",
    selector("setSwapEnabled(bool)"): "trading_switch",
}


def encode_call(sig_selector: str, arg_types: Sequence[str] = (), args: Sequence[Any] = ()) -> str:
    if not arg_types:
        return sig_selector
    return sig_selector + abi_encode(list(arg_types), list(args)).hex()


def _decode_string(raw: str) -> str:
    """Decodifica name()/symbol(). Alcuni token vecchi ritornano bytes32."""
    if not raw or raw == "0x":
        return ""
    data = bytes.fromhex(raw[2:])
    try:
        return abi_decode(["string"], data)[0]
    except Exception:
        try:
            return data.rstrip(b"\x00").decode("utf-8", errors="ignore").strip()
        except Exception:
            return ""


def _decode_uint(raw: str) -> int:
    if not raw or raw == "0x":
        return 0
    try:
        return int(raw, 16)
    except ValueError:
        return 0


def _decode_address(raw: str) -> str:
    if not raw or len(raw) < 66:
        return ""
    return "0x" + raw[-40:].lower()


class RpcClient:
    """Client JSON-RPC con failover sull'endpoint di riserva e batching."""

    def __init__(self, urls: list[str] | None = None) -> None:
        self.urls = urls or settings.rpc_urls or ["https://rpc.mainnet.chain.robinhood.com"]
        self._clients = [HttpClient(timeout=20.0) for _ in self.urls]
        self._active = 0
        self._id = 0

    async def close(self) -> None:
        for client in self._clients:
            await client.close()

    def _next_id(self) -> int:
        self._id += 1
        return self._id

    async def _post(self, payload: Any) -> Any:
        """Prova gli endpoint in ordine; al primo che risponde si ferma."""
        errors = []
        for offset in range(len(self.urls)):
            idx = (self._active + offset) % len(self.urls)
            result = await self._clients[idx].post(self.urls[idx], json=payload, retries=2)
            if result is not None:
                self._active = idx
                return result
            errors.append(self.urls[idx])
        log.warning("nessun endpoint RPC ha risposto (%s)", ", ".join(errors))
        return None

    async def call(self, method: str, params: list | None = None) -> Any:
        payload = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": method,
            "params": params or [],
        }
        result = await self._post(payload)
        if not isinstance(result, dict):
            return None
        if "error" in result:
            log.debug("errore RPC %s: %s", method, result["error"])
            return None
        return result.get("result")

    async def batch(self, calls: list[tuple[str, list]]) -> list[Any]:
        """Manda N chiamate in una sola richiesta HTTP.

        Ritorna sempre una lista lunga quanto `calls`, con None dove la
        singola chiamata e' fallita, cosi' l'indice resta allineato.
        """
        if not calls:
            return []
        payload = []
        id_map: dict[int, int] = {}
        for position, (method, params) in enumerate(calls):
            rpc_id = self._next_id()
            id_map[rpc_id] = position
            payload.append(
                {"jsonrpc": "2.0", "id": rpc_id, "method": method, "params": params or []}
            )
        result = await self._post(payload)
        out: list[Any] = [None] * len(calls)
        if not isinstance(result, list):
            return out
        for item in result:
            if not isinstance(item, dict):
                continue
            position = id_map.get(item.get("id", -1))
            if position is None:
                continue
            out[position] = None if "error" in item else item.get("result")
        return out

    # -- helper di alto livello --------------------------------------------

    async def block_number(self) -> int:
        return _decode_uint(await self.call("eth_blockNumber") or "0x0")

    async def eth_call(self, to: str, data: str, block: str = "latest") -> str | None:
        return await self.call("eth_call", [{"to": to, "data": data}, block])

    async def get_code(self, address: str) -> str:
        return await self.call("eth_getCode", [address, "latest"]) or "0x"

    async def get_logs(
        self,
        from_block: int,
        to_block: int | str = "latest",
        address: str | list[str] | None = None,
        topics: list | None = None,
    ) -> list[dict] | None:
        """Ritorna la lista dei log, oppure None se la chiamata e' fallita.

        La distinzione conta: una lista vuota significa "nessun evento in questo
        intervallo", None significa "l'RPC ha rifiutato" (di solito perche' il
        range e' troppo ampio). Confondere i due casi porta a ridurre il range
        all'infinito su intervalli che sono semplicemente tranquilli.
        """
        params: dict[str, Any] = {
            "fromBlock": hex(from_block),
            "toBlock": to_block if isinstance(to_block, str) else hex(to_block),
        }
        if address:
            params["address"] = address
        if topics:
            params["topics"] = topics
        result = await self.call("eth_getLogs", [params])
        return result if isinstance(result, list) else None

    async def get_block_timestamp(self, block_number: int) -> int:
        block = await self.call("eth_getBlockByNumber", [hex(block_number), False])
        if isinstance(block, dict):
            return _decode_uint(block.get("timestamp", "0x0"))
        return 0

    # -- letture ERC-20 -----------------------------------------------------

    async def erc20_metadata(self, token: str) -> dict:
        """name, symbol, decimals e totalSupply in una sola richiesta HTTP."""
        results = await self.batch(
            [
                ("eth_call", [{"to": token, "data": SEL["name"]}, "latest"]),
                ("eth_call", [{"to": token, "data": SEL["symbol"]}, "latest"]),
                ("eth_call", [{"to": token, "data": SEL["decimals"]}, "latest"]),
                ("eth_call", [{"to": token, "data": SEL["totalSupply"]}, "latest"]),
            ]
        )
        decimals = _decode_uint(results[2] or "0x") or 18
        total_supply_raw = _decode_uint(results[3] or "0x")
        return {
            "name": _decode_string(results[0] or ""),
            "symbol": _decode_string(results[1] or ""),
            "decimals": decimals,
            "total_supply_raw": total_supply_raw,
            "total_supply": total_supply_raw / (10**decimals) if decimals <= 36 else 0,
        }

    async def balance_of(self, token: str, holder: str) -> int:
        data = encode_call(SEL["balanceOf"], ["address"], [to_checksum_address(holder)])
        return _decode_uint(await self.eth_call(token, data) or "0x")

    async def balances_of(self, token: str, holders: list[str]) -> list[int]:
        calls = [
            (
                "eth_call",
                [
                    {
                        "to": token,
                        "data": encode_call(SEL["balanceOf"], ["address"], [to_checksum_address(h)]),
                    },
                    "latest",
                ],
            )
            for h in holders
        ]
        return [_decode_uint(r or "0x") for r in await self.batch(calls)]

    async def read_owner(self, token: str) -> str:
        """Prova owner() e getOwner(): non tutti i token usano lo stesso standard."""
        results = await self.batch(
            [
                ("eth_call", [{"to": token, "data": SEL["owner"]}, "latest"]),
                ("eth_call", [{"to": token, "data": SEL["getOwner"]}, "latest"]),
            ]
        )
        for raw in results:
            addr = _decode_address(raw or "")
            if addr:
                return addr
        return ""

    async def pair_info(self, pair: str) -> dict:
        """token0/token1/factory di un pool: identifica il DEX e il lato quote."""
        results = await self.batch(
            [
                ("eth_call", [{"to": pair, "data": SEL["token0"]}, "latest"]),
                ("eth_call", [{"to": pair, "data": SEL["token1"]}, "latest"]),
                ("eth_call", [{"to": pair, "data": SEL["factory"]}, "latest"]),
            ]
        )
        return {
            "token0": _decode_address(results[0] or ""),
            "token1": _decode_address(results[1] or ""),
            "factory": _decode_address(results[2] or ""),
        }

    async def deployer_of(self, contract: str) -> str:
        """Chi ha creato il contratto, via traccia di creazione se supportata."""
        # Non tutti gli RPC espongono i trace; il fallback e' Blockscout (vedi safety.py).
        result = await self.call("eth_getTransactionReceipt", [contract])
        if isinstance(result, dict):
            return (result.get("from") or "").lower()
        return ""


def bytecode_flags(code: str) -> list[str]:
    """Cerca nel bytecode i selettori delle funzioni pericolose.

    Non e' una decompilazione: e' una ricerca di stringhe. Puo' dare un falso
    positivo se quei 4 byte compaiono per caso, ma in pratica e' affidabile e
    costa una sola chiamata RPC. Un token senza nessuna di queste funzioni non
    puo' ruggarti via codice (resta il rug via liquidita', che controlliamo a parte).
    """
    if not code or code == "0x":
        return []
    lowered = code.lower()
    found = []
    for sel_hex, label in DANGEROUS_SELECTORS.items():
        if sel_hex[2:] in lowered and label not in found:
            found.append(label)
    return found


_rpc: RpcClient | None = None


def get_rpc() -> RpcClient:
    global _rpc
    if _rpc is None:
        _rpc = RpcClient()
    return _rpc


async def probe_chain() -> dict:
    """Verifica che l'RPC risponda e che sia davvero la chain attesa."""
    rpc = get_rpc()
    chain_id_raw, block = await asyncio.gather(rpc.call("eth_chainId"), rpc.block_number())
    chain_id = _decode_uint(chain_id_raw or "0x0")
    ok = chain_id == settings.chain_id and block > 0
    if not ok:
        log.warning(
            "controllo RPC fallito: chain_id=%s (atteso %s), blocco=%s",
            chain_id, settings.chain_id, block,
        )
    return {"ok": ok, "chain_id": chain_id, "block_number": block}
