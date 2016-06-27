package scorex.waves.transaction

import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.app.Application
import scorex.block.BlockField
import scorex.crypto.encode.Base58
import scorex.settings.Settings
import scorex.transaction.LagonakiTransaction.ValidationResult
import scorex.transaction.LagonakiTransaction.ValidationResult.ValidationResult
import scorex.transaction._
import scorex.transaction.state.wallet.Payment
import scorex.utils.NTP
import scorex.wallet.Wallet
import scorex.waves.settings.{Constants, WavesSettings}

/**
  * Waves Transaction Module
  */
class WavesTransactionModule(implicit override val settings: TransactionSettings with Settings, application: Application)
  extends SimpleTransactionModule() {

  override val InitialBalance = Constants.UnitsInWave * Constants.TotalWaves

  val GenesisTransactionsTimestamp = settings.genesisTimestamp
  // TODO: remove asInstanceOf after Scorex update
  val minimumTxFee = settings.asInstanceOf[WavesSettings].minimumTxFee

  /**
    * Sign payment by keys from wallet
    *
    * TODO: Should be moved to Scorex
    */
  def signPayment(payment: Payment, wallet: Wallet): Option[PaymentTransaction] = {
    wallet.privateKeyAccount(payment.sender).map { sender =>
      signPayment(sender, new Account(payment.recipient), payment.amount, payment.fee, NTP.correctedTime())
    }
  }

  /**
    * Create signed PaymentTransaction without broadcasting to network
    *
    * TODO: Should be moved to Scorex
    */
  def signPayment(sender: PrivateKeyAccount, recipient: Account, amount: Long, fee: Long, timestamp: Long): PaymentTransaction = {
    val sig = PaymentTransaction.generateSignature(sender, recipient, amount, fee, timestamp)
    val payment = new PaymentTransaction(sender, recipient, amount, fee, timestamp, sig)
    payment
  }

  /**
    * Create signed payment transaction and validate it through current state.
    */
  def createSignedPayment(sender: PrivateKeyAccount, recipient: Account, amount: Long, fee: Long, timestamp: Long): Either[PaymentTransaction, ValidationResult] = {
    val sig = PaymentTransaction.generateSignature(sender, recipient, amount, fee, timestamp)
    val payment = new PaymentTransaction(sender, recipient, amount, fee, timestamp, sig)

    payment.validate match {
      case ValidationResult.ValidateOke => {
        if (blockStorage.state.isValid(payment)) {
          Left(payment)
        } else {
          Right(ValidationResult.NoBalance)
        }
      }
      case error: ValidationResult => Right(error)
    }
  }


  /**
    * Publish signed payment transaction which generated outside node
    */
  def broadcastPayment(payment: SignedPayment): Either[PaymentTransaction, ValidationResult] = {
    if (payment.fee < minimumTxFee)
      // TODO : add ValidationResult.InvalidFee to Scorex
      Right(ValidationResult.NegativeFee)
    else {
      val time = payment.timestamp
      val sigBytes = Base58.decode(payment.signature).get
      val senderPubKey = Base58.decode(payment.senderPublicKey).get
      val recipientAccount = new Account(payment.recipient)
      val tx = new PaymentTransaction(new PublicKeyAccount(senderPubKey),
        recipientAccount, payment.amount, payment.fee, time, sigBytes)

      tx.validate match {
        case ValidationResult.ValidateOke => {
          if (blockStorage.state.isValid(tx)) {
            onNewOffchainTransaction(tx)
            Left(tx)
          } else {
            Right(ValidationResult.NoBalance)
          }
        }
        case error: ValidationResult => Right(error)
      }
    }
  }

  override def genesisData: BlockField[SimpleTransactionModule.StoredInBlock] = {

    val totalBalance = InitialBalance
    val txs = List(
      GenesisTransaction( new Account("3PAWwWa6GbwcJaFzwqXQN5KQm7H96Y7SHTQ"), totalBalance - 5 * Constants.UnitsInWave, GenesisTransactionsTimestamp),
      GenesisTransaction( new Account("3P8JdJGYc7vaLu4UXUZc1iRLdzrkGtdCyJM"), Constants.UnitsInWave, GenesisTransactionsTimestamp),
      GenesisTransaction( new Account("3PAGPDPqnGkyhcihyjMHe9v36Y4hkAh9yDy"), Constants.UnitsInWave, GenesisTransactionsTimestamp),
      GenesisTransaction( new Account("3P9o3ZYwtHkaU1KxsKkFjJqJKS3dLHLC9oF"), Constants.UnitsInWave, GenesisTransactionsTimestamp),
      GenesisTransaction( new Account("3PJaDyprvekvPXPuAtxrapacuDJopgJRaU3"), Constants.UnitsInWave, GenesisTransactionsTimestamp),
      GenesisTransaction( new Account("3PBWXDFUc86N2EQxKJmW8eFco65xTyMZx6J"), Constants.UnitsInWave, GenesisTransactionsTimestamp)
    )
    require(txs.foldLeft(0L)(_ + _.amount) == InitialBalance)

    TransactionsBlockField(txs)
  }
}
