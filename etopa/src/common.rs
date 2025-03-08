//! Commons

use aes_gcm::aead::generic_array::{
    GenericArray,
    typenum::{
        bit::{B0, B1},
        uint::{UInt, UTerm},
    },
};

/// AES 256-bit key representation
pub type Aes256Key =
    GenericArray<u8, UInt<UInt<UInt<UInt<UInt<UInt<UTerm, B1>, B0>, B0>, B0>, B0>, B0>>;

/// AES-GCM nonce representation
pub type Nonce = GenericArray<u8, UInt<UInt<UInt<UInt<UTerm, B1>, B1>, B0>, B0>>;
